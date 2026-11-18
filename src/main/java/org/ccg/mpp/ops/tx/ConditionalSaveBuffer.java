package org.ccg.mpp.ops.tx;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.Transaction;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.AggregateLimitException;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.SavePatch;
import org.ccg.mpp.ops.ScriptOperationHelper;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.SaveWhereResponse;
import org.ccg.mpp.simplejs.exceptions.ScriptCallableException;

public final class ConditionalSaveBuffer {
    private static final Configuration configuration = Configuration.getInstance();

    private ConditionalSaveBuffer() {
    }

    public static OperationResponse buffer(SaveRequest request, Transaction transaction, Runnable onLockTimeout) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        return OperationResponse.respondOrError(OperationType.SAVE, ErrorCode.ERROR_TRANSACTION, () -> {
            final var lockResult = TransactionBuffer.lockedRefusal(transaction, OperationType.SAVE, dbName, collName, 1,
                    onLockTimeout);
            if (lockResult != null) {
                return lockResult;
            }
            final List<JsonObject> matches;
            try {
                matches = ConditionMatches.of(dbName, collName, request.getCondition(), transaction);
            } catch (ScriptCallableException scriptFailure) {
                return new OperationResponse(OperationType.SAVE, scriptFailure.getMessage(),
                        ScriptOperationHelper.errorCodeFor(scriptFailure.getErrorName()));
            } catch (AggregateLimitException limit) {
                return new OperationResponse(OperationType.SAVE, limit.getErrorCode());
            }
            if (matches.size() > configuration.intValue(ConfigKey.UPDATE_MAX_MATCHES)) {
                return new OperationResponse(OperationType.SAVE, ErrorCode.UPDATE_MATCHES_TOO_MANY);
            }
            if (matches.isEmpty()) {
                return new SaveWhereResponse(0);
            }
            final var merged = new ArrayList<JsonObject>(matches.size());
            for (final var match : matches) {
                merged.add(SavePatch.merged(match, request.getObject()));
            }
            return SaveWhereResponse.answering(TransactionBuffer.bufferBulkSave(SavePatch.bulkSaveOf(request, merged),
                    transaction, onLockTimeout));
        });
    }
}

package org.ccg.mpp.ops.tx;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.Transaction;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.AggregateLimitException;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.ScriptOperationHelper;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.resp.DeleteManyResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.simplejs.exceptions.ScriptCallableException;

public final class ConditionalDeleteBuffer {
    private static final Configuration configuration = Configuration.getInstance();

    private record PreparedDelete(String id, JsonObject payload, long bytes) {
    }

    private ConditionalDeleteBuffer() {
    }

    public static OperationResponse buffer(DeleteRequest request, Transaction transaction, Runnable onLockTimeout) {
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        return OperationResponse.respondOrError(OperationType.DELETE, ErrorCode.ERROR_TRANSACTION, () -> {
            final var lockResult = TransactionBuffer.lockedRefusal(transaction, OperationType.DELETE, projectName,
                    collName, 1, onLockTimeout);
            if (lockResult != null) {
                return lockResult;
            }
            final List<JsonObject> matches;
            try {
                matches = ConditionMatches.of(request.getProjectName(), request.getCollectionName(),
                        request.getCondition(), transaction);
            } catch (ScriptCallableException scriptFailure) {
                return new OperationResponse(OperationType.DELETE, scriptFailure.getMessage(),
                        ScriptOperationHelper.errorCodeFor(scriptFailure.getErrorName()));
            } catch (AggregateLimitException limit) {
                return new OperationResponse(OperationType.DELETE, limit.getErrorCode());
            }
            if (matches.size() > configuration.intValue(ConfigKey.DELETE_MAX_MATCHES)) {
                return new OperationResponse(OperationType.DELETE, ErrorCode.DELETE_MATCHES_TOO_MANY);
            }
            final var collId = Cache.getCollectionIdentifier(projectName, collName);
            final var prepared = new ArrayList<PreparedDelete>(matches.size());
            var totalBytes = 0L;
            for (final var match : matches) {
                final var id = match.get(Globals.PK_FIELD).asJsonString().getValue();
                final var hookError = TransactionWrites.runDeleteBeforeHooks(transaction, collId, projectName, collName,
                        id);
                if (hookError != null) {
                    return hookError;
                }
                final var payload = TransactionBuffer.deletePayload(transaction, collId, projectName, collName, id);
                final var bytes = TransactionBuffer.byteSizeOf(projectName, collName, payload);
                prepared.add(new PreparedDelete(id, payload, bytes));
                totalBytes += bytes;
            }
            if (prepared.isEmpty()) {
                return new DeleteManyResponse(0);
            }
            final var sizeRefusal = TransactionBuffer.sizeRefusal(transaction, OperationType.DELETE, prepared.size(),
                    totalBytes);
            if (sizeRefusal != null) {
                return sizeRefusal;
            }
            for (final var delete : prepared) {
                TransactionBuffer.bufferPreparedDelete(transaction, projectName, collName, collId, delete.id(),
                        delete.payload(), delete.bytes());
            }
            return new DeleteManyResponse(prepared.size());
        });
    }
}

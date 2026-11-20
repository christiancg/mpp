package org.ccg.mpp.ops.tx;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.data.Transaction;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.validate.SchemaBudget;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.BeforeHookContext;
import org.ccg.mpp.ops.BeforeHookOutcome;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.TriggerHelper;
import org.ccg.mpp.ops.resp.OperationResponse;

public final class TransactionWrites {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final Logger logger = Logger.logFor(TransactionWrites.class);

    private TransactionWrites() {
    }

    public static String ensureId(JsonObject object, String requestId) {
        var id = requestId;
        if (id == null) {
            id = object.has(Globals.PK_FIELD)
                    ? object.get(Globals.PK_FIELD).asJsonString().getValue()
                    : UUID.randomUUID().toString();
        }
        object.addProperty(Globals.PK_FIELD, id);
        return id;
    }

    // Hooks run once the session holds the collection lock, so they see the same serialization a
    // non-transactional write does.
    public static BeforeHookOutcome runBeforeHooks(String projectName, String collName, EventType event,
            String actingUser, JsonObject object, String id, OperationType type, SchemaBudget schemaBudget) {
        if (!BeforeHookContext.hasHooksFor(projectName, collName, event)) {
            return BeforeHookOutcome.accepted(object);
        }
        try (var hooks = BeforeHookContext.open(projectName, collName, event, actingUser, schemaBudget)) {
            return hooks.apply(object, id, type);
        }
    }

    public static OperationResponse runDeleteBeforeHooks(Transaction transaction, String collId, String projectName,
            String collName, String id) {
        if (!BeforeHookContext.hasHooksFor(projectName, collName, EventType.DELETED)) {
            return null;
        }
        final JsonObject document;
        try {
            document = effectiveDocumentFor(transaction, collId, projectName, collName, id);
        } catch (IOException e) {
            logger.error(OperationType.DELETE + " failed with " + ErrorCode.ERROR_DELETING.getCode(), e);
            return new OperationResponse(OperationType.DELETE, ErrorCode.ERROR_DELETING);
        }
        if (document == null) {
            return null;
        }
        try (var hooks = BeforeHookContext.open(projectName, collName, EventType.DELETED,
                clientTracker.getAuthenticatedUsername(transaction.getClientId()))) {
            final var outcome = hooks.apply(document, id, OperationType.DELETE);
            return outcome.isRejected() ? outcome.rejection() : null;
        }
    }

    public static JsonObject effectiveDocumentFor(Transaction transaction, String collId, String projectName,
            String collName, String id) throws IOException {
        final var overlay = transaction.overlayFor(collId);
        if (overlay != null && overlay.containsKey(id)) {
            final var buffered = overlay.get(id);
            return Transaction.isTombstone(buffered) ? null : buffered;
        }
        final var entries = cache.getEntriesByIds(projectName, collName, Set.of(id));
        return entries.isEmpty() ? null : entries.getFirst().getData();
    }

    public static JsonObject documentForDeletedTrigger(Transaction transaction, String collId, String projectName,
            String collName, String id) {
        final var depth = transaction.getTriggerDepth();
        if (!TriggerHelper.firesOnDelete(projectName, collName, depth)) {
            return null;
        }
        final var overlay = transaction.overlayFor(collId);
        final var buffered = overlay == null ? null : overlay.get(id);
        if (buffered != null && !Transaction.isTombstone(buffered)) {
            return buffered;
        }
        final var captured = TriggerHelper.captureForDelete(projectName, collName, id, depth);
        return captured == null ? null : captured.getData();
    }

    public static boolean isVisible(Transaction transaction, String collId, List<PkIndexEntry> primaryKeyIndex,
            String id) {
        final var overlay = transaction.overlayFor(collId);
        if (overlay != null && overlay.containsKey(id)) {
            return !Transaction.isTombstone(overlay.get(id));
        }
        return Collections.binarySearch(primaryKeyIndex, id) >= 0;
    }
}

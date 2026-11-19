package org.ccg.mpp.cluster;

import java.util.List;
import org.ccg.mpp.cluster.msg.AntiEntropyPayload;
import org.ccg.mpp.cluster.msg.ReplicationOp;
import org.ccg.mpp.cluster.msg.ReplicationPayload;
import org.ccg.mpp.cluster.msg.TxReplicationPayload;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ops.req.validations.NameValidations;

public final class PeerNames {
    private PeerNames() {
    }

    public static boolean collection(String projectName, String collName) {
        return NameValidations.isName(projectName) && NameValidations.isName(collName)
                && !NameValidations.isReservedProjectName(projectName);
    }

    public static boolean payload(ReplicationPayload payload) {
        if (payload == null || payload.getOp() == null
                || !collection(payload.getProjectName(), payload.getCollName())) {
            return false;
        }
        return payload.getOp() == ReplicationOp.UPSERT ? documents(payload.getDocuments()) : ids(payload.getIds());
    }

    public static boolean txPayload(TxReplicationPayload payload) {
        if (payload == null || payload.getEntries().isEmpty()) {
            return false;
        }
        for (final var entry : payload.getEntries()) {
            if (!payload(entry)) {
                return false;
            }
        }
        return true;
    }

    public static boolean antiEntropy(AntiEntropyPayload payload) {
        return payload != null && collection(payload.getProjectName(), payload.getCollName()) && buckets(payload);
    }

    private static boolean buckets(AntiEntropyPayload payload) {
        final var bucketCount = payload.getBucketCount();
        if (bucketCount == 0) {
            return payload.getBuckets() == null;
        }
        if (!DigestHash.isBucketCount(bucketCount)) {
            return false;
        }
        if (payload.getBuckets() == null) {
            return true;
        }
        if (payload.getBuckets().size() > bucketCount) {
            return false;
        }
        for (final var bucket : payload.getBuckets()) {
            if (bucket == null || bucket < 0 || bucket >= bucketCount) {
                return false;
            }
        }
        return true;
    }

    private static boolean documents(List<JsonObject> documents) {
        if (documents == null) {
            return false;
        }
        for (final var document : documents) {
            if (document == null || !(document.get(Globals.PK_FIELD) instanceof JsonString id)
                    || !NameValidations.isId(id.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean ids(List<String> ids) {
        if (ids == null) {
            return false;
        }
        for (final var id : ids) {
            if (!NameValidations.isId(id)) {
                return false;
            }
        }
        return true;
    }
}

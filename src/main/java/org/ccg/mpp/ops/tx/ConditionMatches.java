package org.ccg.mpp.ops.tx;

import java.io.IOException;
import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.data.Transaction;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.AggregationOperationHelper;
import org.ccg.mpp.ops.PipelineContext;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.agg.BaseOperator;
import org.ccg.mpp.ops.req.agg.step.FilterAggregationStep;

final class ConditionMatches {
    private ConditionMatches() {
    }

    static List<JsonObject> of(String dbName, String collName, BaseOperator condition, Transaction transaction)
            throws IOException {
        final var filter = new AggregateRequest(dbName, collName);
        filter.setAggregationSteps(List.of(new FilterAggregationStep(condition)));
        final var overlay = transaction.overlayFor(Cache.getCollectionIdentifier(dbName, collName));
        try (var context = PipelineContext.fromConfiguration(null)) {
            return overlay == null || overlay.isEmpty()
                    ? AggregationOperationHelper.processAggregation(filter, null, transaction, context)
                    : OverlayRead.runPipeline(filter, transaction, context);
        }
    }
}

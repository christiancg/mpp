package org.ccg.mpp.ops;

import java.util.function.Predicate;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;
import org.ccg.mpp.ops.req.agg.step.FilterAggregationStep;
import org.ccg.mpp.utils.RangeBounds;

record SingleRange(RangeBounds bounds, Predicate<JsonObject> matches) {
    static SingleRange of(BaseAggregationStep step) {
        if (!(step instanceof FilterAggregationStep filter)) {
            return null;
        }
        final var leaves = ConjunctionCandidates.singleRangeLeaves(filter.getOperator());
        if (leaves == null) {
            return null;
        }
        final var fieldName = leaves.getFirst().getField();
        final var everyLeaf = ConjunctionCandidates.everyLeaf(leaves);
        return new SingleRange(RangeBounds.fromLeaves(fieldName, leaves),
                document -> everyLeaf.test(document, fieldName));
    }
}

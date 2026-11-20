package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;

final class IndexedSource {
    private IndexedSource() {
    }

    record Fast(Stream<JsonObject> stream, int nextStepIndex) {
    }

    static Fast of(List<BaseAggregationStep> steps, String projectName, String collName, long boundAfterSecondStep,
            PipelineContext context) throws IOException {
        final var fastCount = CountOperatorHelper.tryIndexOnlyCount(steps, projectName, collName, context);
        if (fastCount != null) {
            return new Fast(Stream.of(fastCount.result()), fastCount.nextStepIndex());
        }
        final var topK = SortOperatorHelper.tryIndexedRangeTopK(steps, projectName, collName, boundAfterSecondStep,
                context);
        if (topK != null) {
            return topK;
        }
        return IndexedFilterLimit.tryLazy(steps, projectName, collName, context);
    }
}

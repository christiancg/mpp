package org.ccg.mpp.ops;

import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.agg.AggregationStepType;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;
import org.ccg.mpp.ops.req.agg.FieldOperatorType;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;
import org.ccg.mpp.ops.req.agg.step.FilterAggregationStep;
import org.ccg.mpp.utils.JsonUtils;

public final class PointServablePipeline {
    private static final Cache cache = IocContainer.get(Cache.class);

    private PointServablePipeline() {
    }

    public static boolean test(AggregateRequest request) {
        final var steps = request.getAggregationSteps();
        if (steps == null || steps.isEmpty()
                || steps.stream().anyMatch(step -> step.getType() == AggregationStepType.JOIN)) {
            return false;
        }
        final var source = steps.getFirst();
        if (source.getType() == AggregationStepType.COUNT) {
            return true;
        }
        if (!(source instanceof FilterAggregationStep filter)
                || !(filter.getOperator() instanceof FieldOperator operator) || !looksUpPoints(operator)) {
            return false;
        }
        if (JsonUtils.isPrimaryKeyPath(operator.getField())) {
            return operator.getFieldOperatorType() == FieldOperatorType.EQUALS
                    || operator.getFieldOperatorType() == FieldOperatorType.IN;
        }
        return (AggregationOperationHelper.sortBound(steps, 0) > 0 || countsFromIndex(steps))
                && !cache.hasNoIndex(request.getProjectName(), request.getCollectionName(), operator.getField());
    }

    private static boolean looksUpPoints(FieldOperator operator) {
        final var value = operator.getValue();
        if (value == null || value.isJsonObject()) {
            return false;
        }
        return switch (operator.getFieldOperatorType()) {
            case EQUALS, GREATER_THAN, GREATER_THAN_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS -> !value.isJsonArray();
            case IN -> value.isJsonArray();
            default -> false;
        };
    }

    private static boolean countsFromIndex(List<BaseAggregationStep> steps) {
        for (final var step : steps) {
            switch (step.getType()) {
                case COUNT -> {
                    return true;
                }
                case SORT -> {
                }
                case FILTER -> {
                    if (!(((FilterAggregationStep) step).getOperator() instanceof FieldOperator)) {
                        return false;
                    }
                }
                default -> {
                    return false;
                }
            }
        }
        return false;
    }
}

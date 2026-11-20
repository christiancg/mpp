package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.List;
import java.util.OptionalLong;
import org.ccg.mpp.analyze.AnalyzeContext;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;
import org.ccg.mpp.utils.JsonUtils;
import org.ccg.mpp.utils.RangeBounds;

final class ScanPreference {
    static final int MIN_ROWS = 4096;
    private static final int ALWAYS_INDEX = 100;
    private static final Configuration configuration = Configuration.getInstance();
    private static final Cache cache = IocContainer.get(Cache.class);

    private ScanPreference() {
    }

    static boolean tooBroad(long matched, String projectName, String collName) {
        final var percent = percent();
        if (percent >= ALWAYS_INDEX || matched * ALWAYS_INDEX <= (long) percent * MIN_ROWS) {
            return false;
        }
        final long rows = cache.pkIndexSize(projectName, collName);
        return rows >= MIN_ROWS && matched * ALWAYS_INDEX > percent * rows;
    }

    static boolean prefersScan(FieldOperator operator, String projectName, String collName, PipelineContext context)
            throws IOException {
        final var fieldName = operator.getField();
        if (percent() >= ALWAYS_INDEX || JsonUtils.isPrimaryKeyPath(fieldName)
                || cache.hasNoIndex(projectName, collName, fieldName)) {
            return false;
        }
        final var matched = countedMatches(operator, projectName, collName);
        if (matched.isEmpty() || !tooBroad(matched.getAsLong(), projectName, collName)) {
            return false;
        }
        context.indexUsed(fieldName, AnalyzeContext.fieldLockId(projectName, collName, fieldName));
        return true;
    }

    static boolean indexFetchNotWorthIt(String projectName, String collName) {
        return groupByPrefersScan(projectName, collName) && !cache.holdsWholeCollection(projectName, collName);
    }

    static boolean groupByPrefersScan(String projectName, String collName) {
        return percent() < ALWAYS_INDEX && cache.pkIndexSize(projectName, collName) >= MIN_ROWS;
    }

    private static OptionalLong countedMatches(FieldOperator operator, String projectName, String collName)
            throws IOException {
        final var fieldName = operator.getField();
        return switch (operator.getFieldOperatorType()) {
            case EQUALS -> {
                final var value = CountOperatorHelper.scalarOperand(operator.getValue());
                yield value == null ? OptionalLong.empty() : cache.countEquals(projectName, collName, fieldName, value);
            }
            case NOT_EQUALS -> complementOf(CountOperatorHelper.scalarOperands(List.of(operator.getValue())),
                    projectName, collName, fieldName);
            case NOT_IN -> operator.getValue() instanceof JsonArray operands
                    ? complementOf(CountOperatorHelper.scalarOperands(operands.asList()), projectName, collName,
                            fieldName)
                    : OptionalLong.empty();
            default -> RangeBounds.isNumericRange(operator)
                    ? cache.countInRange(projectName, collName, RangeBounds.fromLeaves(fieldName, List.of(operator)))
                    : OptionalLong.empty();
        };
    }

    private static OptionalLong complementOf(List<Object> operands, String projectName, String collName,
            String fieldName) throws IOException {
        if (operands == null) {
            return OptionalLong.empty();
        }
        final var matched = cache.countEqualsAny(projectName, collName, fieldName, operands);
        return matched.isEmpty()
                ? matched
                : OptionalLong.of(Math.max(0L, cache.pkIndexSize(projectName, collName) - matched.getAsLong()));
    }

    private static int percent() {
        return configuration.intValue(ConfigKey.INDEX_SCAN_FALLBACK_PERCENT);
    }
}

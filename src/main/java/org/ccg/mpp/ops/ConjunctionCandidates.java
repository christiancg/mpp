package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import org.ccg.mpp.analyze.AnalyzeContext;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.filter.FieldPredicateFactory;
import org.ccg.mpp.ops.index.PendingWriteReconciler;
import org.ccg.mpp.ops.req.agg.BaseOperator;
import org.ccg.mpp.ops.req.agg.ConjunctionOperatorType;
import org.ccg.mpp.ops.req.agg.OperatorType;
import org.ccg.mpp.ops.req.agg.operators.ConjunctionOperator;
import org.ccg.mpp.ops.req.agg.operators.CustomOperator;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;
import org.ccg.mpp.utils.JsonUtils;
import org.ccg.mpp.utils.RangeBounds;

final class ConjunctionCandidates {
    private static final Cache cache = IocContainer.get(Cache.class);

    private interface LegResolver {
        Set<String> resolve(BaseOperator leg) throws IOException;
    }

    private ConjunctionCandidates() {
    }

    static Set<String> of(ConjunctionOperator operator, String projectName, String collName, PipelineContext context)
            throws IOException {
        if (OperatorPredicate.holdsRanking(operator)) {
            return null;
        }
        final LegResolver filterLeg = leg -> switch (leg.getType()) {
            case FIELD -> FilterOperatorHelper.indexMatchingIds((FieldOperator) leg, projectName, collName, context);
            case CONJUNCTION -> of((ConjunctionOperator) leg, projectName, collName, context);
            case CUSTOM -> GeoSpatialIndexHelper.candidateIds((CustomOperator) leg, projectName, collName, context);
            case SCRIPT -> null;
        };
        return switch (operator.getConjunctionType()) {
            case AND ->
                intersection(SelectiveLegs.sets(operator, projectName, collName, context, filterLeg::resolve), context);
            case OR, XOR -> unionOfEvery(legSets(operator, projectName, collName, context, filterLeg), context);
            case NOR, NAND -> null;
        };
    }

    static Set<String> exactAndIds(ConjunctionOperator operator, String projectName, String collName,
            PipelineContext context) throws IOException {
        final LegResolver exactLeg = leg -> FilterOperatorHelper.resolveIdsViaIndex(leg, projectName, collName,
                context);
        final var sets = legSets(operator, projectName, collName, context, exactLeg);
        return sets.contains(null) ? null : intersection(sets, context);
    }

    static List<FieldOperator> singleRangeLeaves(BaseOperator operator) {
        final var leaves = new ArrayList<FieldOperator>();
        if (operator.getType() == OperatorType.FIELD) {
            leaves.add((FieldOperator) operator);
        } else if (operator instanceof ConjunctionOperator conjunction
                && conjunction.getConjunctionType() == ConjunctionOperatorType.AND) {
            for (final var leg : conjunction.getOperators()) {
                if (leg.getType() != OperatorType.FIELD) {
                    return null;
                }
                leaves.add((FieldOperator) leg);
            }
        }
        if (leaves.isEmpty() || !leaves.stream().allMatch(ConjunctionCandidates::isMergeableRange)
                || leaves.stream().map(FieldOperator::getField).distinct().count() != 1) {
            return null;
        }
        return leaves;
    }

    static Set<String> rangeMatchingIds(RangeBounds bounds, List<FieldOperator> leaves, String projectName,
            String collName, PipelineContext context) throws IOException {
        final var fieldName = bounds.fieldName();
        if (cache.hasNoIndex(projectName, collName, fieldName)) {
            return null;
        }
        final var pendingBefore = PendingWriteReconciler.pendingIds(projectName, collName);
        final var raw = cache.getIdsInRange(projectName, collName, bounds);
        if (raw == null) {
            return null;
        }
        context.indexUsed(fieldName, AnalyzeContext.fieldLockId(projectName, collName, fieldName));
        final var pendingIds = PendingWriteReconciler.pendingIdsAround(pendingBefore, projectName, collName);
        if (pendingIds.isEmpty()) {
            return raw;
        }
        return PendingWriteReconciler.correctIds(raw, projectName, collName, pendingIds, fieldName, everyLeaf(leaves),
                context);
    }

    private static List<Set<String>> legSets(ConjunctionOperator operator, String projectName, String collName,
            PipelineContext context, LegResolver resolver) throws IOException {
        final var sets = new ArrayList<Set<String>>();
        final var rangesByField = new LinkedHashMap<String, List<FieldOperator>>();
        final var mergesRanges = operator.getConjunctionType() == ConjunctionOperatorType.AND;
        for (final var leg : operator.getOperators()) {
            if (mergesRanges && leg instanceof FieldOperator field && isMergeableRange(field)) {
                rangesByField.computeIfAbsent(field.getField(), _ -> new ArrayList<>()).add(field);
                continue;
            }
            sets.add(resolver.resolve(leg));
        }
        for (final Map.Entry<String, List<FieldOperator>> range : rangesByField.entrySet()) {
            final var bounds = RangeBounds.fromLeaves(range.getKey(), range.getValue());
            sets.add(rangeMatchingIds(bounds, range.getValue(), projectName, collName, context));
        }
        return sets;
    }

    static boolean isMergeableRange(FieldOperator leaf) {
        return RangeBounds.isNumericRange(leaf) && !JsonUtils.isPrimaryKeyPath(leaf.getField());
    }

    private static Set<String> intersection(List<Set<String>> sets, PipelineContext context) {
        if (sets.isEmpty()) {
            return null;
        }
        final var smallestFirst = sets.stream().sorted(Comparator.comparingInt(Set::size)).toList();
        context.tick(smallestFirst.getFirst().size());
        final var survivors = new HashSet<>(smallestFirst.getFirst());
        for (var i = 1; i < smallestFirst.size() && !survivors.isEmpty(); i++) {
            context.tick(survivors.size());
            survivors.retainAll(smallestFirst.get(i));
        }
        return survivors;
    }

    private static Set<String> unionOfEvery(List<Set<String>> sets, PipelineContext context) {
        if (sets.contains(null)) {
            return null;
        }
        final var union = new HashSet<String>();
        for (final var set : sets) {
            context.tick(set.size());
            union.addAll(set);
        }
        return union;
    }

    static BiPredicate<JsonObject, String> everyLeaf(List<FieldOperator> leaves) {
        final var testers = leaves.stream()
                .map(leaf -> FieldPredicateFactory.getTester(leaf, leaf.getFieldOperatorType())).toList();
        return (document, fieldName) -> testers.stream().allMatch(tester -> tester.test(document, fieldName));
    }
}

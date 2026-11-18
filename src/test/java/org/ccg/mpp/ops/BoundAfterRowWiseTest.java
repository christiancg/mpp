package org.ccg.mpp.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;
import org.ccg.mpp.ops.req.agg.FieldOperatorType;
import org.ccg.mpp.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.ccg.mpp.ops.req.agg.mid_operators.MidOperationType;
import org.ccg.mpp.ops.req.agg.mid_operators.ScriptMidOperator;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;
import org.ccg.mpp.ops.req.agg.operators.ScriptOperator;
import org.ccg.mpp.ops.req.agg.step.CountAggregationStep;
import org.ccg.mpp.ops.req.agg.step.FilterAggregationStep;
import org.ccg.mpp.ops.req.agg.step.LimitAggregationStep;
import org.ccg.mpp.ops.req.agg.step.MapAggregationStep;
import org.ccg.mpp.ops.req.agg.step.SkipAggregationStep;
import org.ccg.mpp.ops.req.agg.step.SortAggregationStep;
import org.ccg.mpp.ops.req.agg.step.map.AddFieldMapOperator;
import org.ccg.mpp.ops.req.agg.step.map.RemoveFieldMapOperator;
import org.junit.jupiter.api.Test;

public class BoundAfterRowWiseTest {
    private static final FilterAggregationStep SOURCE = new FilterAggregationStep(
            new FieldOperator(FieldOperatorType.EQUALS, "cat", new JsonString("c1")));

    private static long bound(BaseAggregationStep... after) {
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(SOURCE);
        steps.addAll(List.of(after));
        return AggregationOperationHelper.boundAfterRowWise(steps, 0);
    }

    private static MapAggregationStep arithmeticMap() {
        final var operands = new JsonArray();
        operands.add(new JsonString("ts"));
        operands.add(new JsonNumber(2));
        return new MapAggregationStep(List.of(new AddFieldMapOperator("twice", null,
                new ArrayParamMidOperator(MidOperationType.MULTIPLY, operands))));
    }

    @Test
    public void rowWiseStepsBeforeTheBoundAreSkipped() {
        assertEquals(5, bound(new LimitAggregationStep(5)));
        assertEquals(12, bound(new SkipAggregationStep(7), new LimitAggregationStep(5)));
        assertEquals(5, bound(arithmeticMap(), new LimitAggregationStep(5)));
        assertEquals(12, bound(SOURCE, arithmeticMap(), new SkipAggregationStep(7), new LimitAggregationStep(5)));
        assertEquals(5, bound(new MapAggregationStep(List.of(new RemoveFieldMapOperator("ts", null))),
                new LimitAggregationStep(5)));
    }

    @Test
    public void scriptsAndHoldingStepsEndTheSearch() {
        assertEquals(-1,
                bound(new MapAggregationStep(
                        List.of(new AddFieldMapOperator("s", null, new ScriptMidOperator("x => 1")))),
                        new LimitAggregationStep(5)));
        assertEquals(-1,
                bound(new MapAggregationStep(
                        List.of(new RemoveFieldMapOperator("ts", new ScriptOperator("x => true")))),
                        new LimitAggregationStep(5)));
        assertEquals(-1,
                bound(new FilterAggregationStep(new ScriptOperator("x => true")), new LimitAggregationStep(5)));
        assertEquals(-1, bound(new SortAggregationStep("ts", true), new LimitAggregationStep(5)));
        assertEquals(-1, bound(new CountAggregationStep()));
        assertEquals(-1, bound(arithmeticMap()));
        assertEquals(-1, bound(new SkipAggregationStep(3)));
    }
}

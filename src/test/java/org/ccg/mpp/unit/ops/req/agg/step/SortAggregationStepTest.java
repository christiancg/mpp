package org.ccg.mpp.unit.ops.req.agg.step;

import static org.junit.jupiter.api.Assertions.*;

import org.ccg.mpp.ops.req.agg.AggregationStepType;
import org.ccg.mpp.ops.req.agg.step.SortAggregationStep;
import org.junit.jupiter.api.Test;

public class SortAggregationStepTest {
    @Test
    public void test_constructor_with_valid_params() {
        String fieldName = "name";
        boolean ascending = true;

        SortAggregationStep step = new SortAggregationStep(fieldName, ascending);

        assertEquals(AggregationStepType.SORT, step.getType());
        assertEquals(fieldName, step.getFieldName());
        assertEquals(ascending, step.getAscending());
    }

    @Test
    public void test_constructor_with_null_field_name() {
        boolean ascending = true;

        SortAggregationStep step = new SortAggregationStep(null, ascending);

        assertEquals(AggregationStepType.SORT, step.getType());
        assertNull(step.getFieldName());
        assertEquals(ascending, step.getAscending());
    }

    @Test
    public void test_getters_and_setters() {
        SortAggregationStep step = new SortAggregationStep("name", true);

        assertEquals("name", step.getFieldName());
        assertTrue(step.getAscending());

        step.setFieldName("newName");
        step.setAscending(false);

        assertEquals("newName", step.getFieldName());
        assertFalse(step.getAscending());
    }
}

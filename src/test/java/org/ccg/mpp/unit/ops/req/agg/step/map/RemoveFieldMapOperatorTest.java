package org.ccg.mpp.unit.ops.req.agg.step.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.ccg.mpp.ops.req.agg.BaseOperator;
import org.ccg.mpp.ops.req.agg.OperatorType;
import org.ccg.mpp.ops.req.agg.step.map.MapOperationType;
import org.ccg.mpp.ops.req.agg.step.map.RemoveFieldMapOperator;
import org.junit.jupiter.api.Test;

public class RemoveFieldMapOperatorTest {
    @Test
    public void test_constructor_sets_remove_field_type() {
        String fieldName = "testField";
        BaseOperator condition = new BaseOperator(OperatorType.FIELD);

        RemoveFieldMapOperator operator = new RemoveFieldMapOperator(fieldName, condition);

        assertEquals(MapOperationType.REMOVE_FIELD, operator.getType());
        assertEquals(fieldName, operator.getFieldName());
        assertEquals(condition, operator.getCondition());
    }

    @Test
    public void test_constructor_with_null_field_name() {
        BaseOperator condition = new BaseOperator(OperatorType.FIELD);

        RemoveFieldMapOperator operator = new RemoveFieldMapOperator(null, condition);

        assertNull(operator.getFieldName());
        assertEquals(MapOperationType.REMOVE_FIELD, operator.getType());
        assertEquals(condition, operator.getCondition());
    }
}

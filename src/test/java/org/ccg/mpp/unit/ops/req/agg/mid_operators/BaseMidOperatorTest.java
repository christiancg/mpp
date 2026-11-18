package org.ccg.mpp.unit.ops.req.agg.mid_operators;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ops.req.agg.mid_operators.BaseMidOperator;
import org.ccg.mpp.ops.req.agg.mid_operators.MidOperationType;
import org.junit.jupiter.api.Test;

public class BaseMidOperatorTest {
    @Test
    public void test_constructor_initializes_type_field() {
        MidOperationType expectedType = MidOperationType.AVG;
        BaseMidOperator operator = new BaseMidOperator(expectedType);

        assertEquals(expectedType, operator.getType());
    }
}

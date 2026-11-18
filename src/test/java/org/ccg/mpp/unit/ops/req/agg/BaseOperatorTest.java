package org.ccg.mpp.unit.ops.req.agg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ops.req.agg.BaseOperator;
import org.ccg.mpp.ops.req.agg.OperatorType;
import org.junit.jupiter.api.Test;

public class BaseOperatorTest {
    @Test
    public void test_constructor_creates_base_operator_with_type() {
        BaseOperator operator = new BaseOperator(OperatorType.FIELD);

        assertEquals(OperatorType.FIELD, operator.getType());
    }
}

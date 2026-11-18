package org.ccg.mpp.unit.ops.req.agg.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import org.ccg.mpp.ops.req.agg.BaseOperator;
import org.ccg.mpp.ops.req.agg.ConjunctionOperatorType;
import org.ccg.mpp.ops.req.agg.OperatorType;
import org.ccg.mpp.ops.req.agg.operators.ConjunctionOperator;
import org.junit.jupiter.api.Test;

public class ConjunctionOperatorTest {
    @Test
    public void test_create_conjunction_operator_with_valid_type_and_operators() {
        List<BaseOperator> operators = Arrays.asList(new BaseOperator(OperatorType.FIELD),
                new BaseOperator(OperatorType.FIELD));

        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(ConjunctionOperatorType.AND, operators);

        assertEquals(ConjunctionOperatorType.AND, conjunctionOperator.getConjunctionType());
        assertEquals(operators, conjunctionOperator.getOperators());
        assertEquals(OperatorType.CONJUNCTION, conjunctionOperator.getType());
    }

    @Test
    public void test_create_conjunction_operator_with_null_type() {
        List<BaseOperator> operators = List.of(new BaseOperator(OperatorType.FIELD));

        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(null, operators);

        assertNull(conjunctionOperator.getConjunctionType());
        assertEquals(operators, conjunctionOperator.getOperators());
        assertEquals(OperatorType.CONJUNCTION, conjunctionOperator.getType());
    }

    @Test
    public void test_getters_and_setters() {
        List<BaseOperator> operators = Arrays.asList(new BaseOperator(OperatorType.FIELD),
                new BaseOperator(OperatorType.FIELD));

        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(ConjunctionOperatorType.OR, operators);

        assertEquals(ConjunctionOperatorType.OR, conjunctionOperator.getConjunctionType());
        assertEquals(operators, conjunctionOperator.getOperators());

        conjunctionOperator.setConjunctionType(ConjunctionOperatorType.AND);
        assertEquals(ConjunctionOperatorType.AND, conjunctionOperator.getConjunctionType());

        List<BaseOperator> newOperators = List.of(new BaseOperator(OperatorType.CONJUNCTION));
        conjunctionOperator.setOperators(newOperators);
        assertEquals(newOperators, conjunctionOperator.getOperators());
    }
}

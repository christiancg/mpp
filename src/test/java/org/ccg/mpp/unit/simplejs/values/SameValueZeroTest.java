package org.ccg.mpp.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.ccg.mpp.simplejs.values.JsBigInt;
import org.ccg.mpp.simplejs.values.JsBoolean;
import org.ccg.mpp.simplejs.values.JsNull;
import org.ccg.mpp.simplejs.values.JsNumber;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsString;
import org.ccg.mpp.simplejs.values.JsUndefined;
import org.ccg.mpp.simplejs.values.SameValueZero;
import org.junit.jupiter.api.Test;

public class SameValueZeroTest {
    @Test
    public void test_nan_is_self_equal() {
        assertTrue(SameValueZero.equal(new JsNumber(Double.NaN), new JsNumber(Double.NaN)));
    }

    @Test
    public void test_signed_zero_collapses() {
        assertTrue(SameValueZero.equal(new JsNumber(0.0), new JsNumber(-0.0)));
    }

    @Test
    public void test_primitives_compare_by_value() {
        assertTrue(SameValueZero.equal(new JsNumber(1), new JsNumber(1)));
        assertTrue(SameValueZero.equal(new JsString("a"), new JsString("a")));
        assertTrue(SameValueZero.equal(JsBoolean.TRUE, JsBoolean.of(true)));
        assertTrue(SameValueZero.equal(new JsBigInt(BigInteger.ONE), new JsBigInt(BigInteger.ONE)));
        assertTrue(SameValueZero.equal(JsNull.getInstance(), JsNull.getInstance()));
        assertTrue(SameValueZero.equal(JsUndefined.getInstance(), JsUndefined.getInstance()));
    }

    @Test
    public void test_unequal_values() {
        assertFalse(SameValueZero.equal(new JsNumber(1), new JsNumber(2)));
        assertFalse(SameValueZero.equal(new JsNumber(1), new JsString("1")));
        assertFalse(SameValueZero.equal(JsNull.getInstance(), JsUndefined.getInstance()));
    }

    @Test
    public void test_objects_compare_by_identity() {
        final var object = new JsObject();
        assertTrue(SameValueZero.equal(object, object));
        assertFalse(SameValueZero.equal(object, new JsObject()));
    }
}

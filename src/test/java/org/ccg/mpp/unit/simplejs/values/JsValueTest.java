package org.ccg.mpp.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import org.ccg.mpp.simplejs.internal.Coroutine;
import org.ccg.mpp.simplejs.internal.Environment;
import org.ccg.mpp.simplejs.internal.EventLoop;
import org.ccg.mpp.simplejs.internal.JsCoercion;
import org.ccg.mpp.simplejs.values.JsArray;
import org.ccg.mpp.simplejs.values.JsBigInt;
import org.ccg.mpp.simplejs.values.JsBoolean;
import org.ccg.mpp.simplejs.values.JsClass;
import org.ccg.mpp.simplejs.values.JsFunction;
import org.ccg.mpp.simplejs.values.JsGenerator;
import org.ccg.mpp.simplejs.values.JsNativeFunction;
import org.ccg.mpp.simplejs.values.JsNull;
import org.ccg.mpp.simplejs.values.JsNumber;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsPromise;
import org.ccg.mpp.simplejs.values.JsString;
import org.ccg.mpp.simplejs.values.JsSymbol;
import org.ccg.mpp.simplejs.values.JsUndefined;
import org.ccg.mpp.simplejs.values.JsValue;
import org.junit.jupiter.api.Test;

public class JsValueTest {
    @Test
    public void test_get_type_for_each_value() {
        assertEquals(JsValue.JsValueType.NUMBER, new JsNumber(1).getType());
        assertEquals(JsValue.JsValueType.STRING, new JsString("a").getType());
        assertEquals(JsValue.JsValueType.BOOLEAN, JsBoolean.TRUE.getType());
        assertEquals(JsValue.JsValueType.BIGINT, new JsBigInt(BigInteger.ONE).getType());
        assertEquals(JsValue.JsValueType.UNDEFINED, JsUndefined.getInstance().getType());
        assertEquals(JsValue.JsValueType.NULL, JsNull.getInstance().getType());
        assertEquals(JsValue.JsValueType.OBJECT, new JsObject().getType());
        assertEquals(JsValue.JsValueType.ARRAY, new JsArray().getType());
        assertEquals(JsValue.JsValueType.FUNCTION,
                new JsFunction(null, List.of(), null, false, false, false, false, Environment.global()).getType());
        assertEquals(JsValue.JsValueType.FUNCTION,
                new JsNativeFunction("id", (_, _) -> JsUndefined.getInstance()).getType());
        assertEquals(JsValue.JsValueType.CLASS, new JsClass("A", null, Environment.global()).getType());
        assertEquals(JsValue.JsValueType.PROMISE, new JsPromise(new EventLoop()).getType());
        assertEquals(JsValue.JsValueType.GENERATOR, new JsGenerator(new Coroutine()).getType());
        assertEquals(JsValue.JsValueType.MAP, new org.ccg.mpp.simplejs.values.JsMap().getType());
        assertEquals(JsValue.JsValueType.SET, new org.ccg.mpp.simplejs.values.JsSet().getType());
        assertEquals(JsValue.JsValueType.DATE, new org.ccg.mpp.simplejs.values.JsDate(0).getType());
        assertEquals(JsValue.JsValueType.PROXY,
                new org.ccg.mpp.simplejs.values.JsProxy(new JsObject(), new JsObject()).getType());
        assertEquals(JsValue.JsValueType.ARGUMENTS,
                new org.ccg.mpp.simplejs.values.JsArguments(List.of(), null, null).getType());
        assertEquals(JsValue.JsValueType.GLOBAL,
                new org.ccg.mpp.simplejs.values.JsGlobalObject(Environment.global()).getType());
        assertEquals(JsValue.JsValueType.ARRAY_BUFFER, new org.ccg.mpp.simplejs.values.JsArrayBuffer(4).getType());
        assertEquals(JsValue.JsValueType.TYPED_ARRAY,
                new org.ccg.mpp.simplejs.values.JsTypedArray(org.ccg.mpp.simplejs.values.JsTypedArray.Kind.INT8,
                        new org.ccg.mpp.simplejs.values.JsArrayBuffer(1), 0, 1).getType());
        assertEquals(JsValue.JsValueType.DATA_VIEW,
                new org.ccg.mpp.simplejs.values.JsDataView(new org.ccg.mpp.simplejs.values.JsArrayBuffer(4), 0, 4)
                        .getType());
        assertEquals(JsValue.JsValueType.GEO,
                new org.ccg.mpp.simplejs.values.JsGeo(new org.ccg.mpp.utils.GeoPoint(1, 2)).getType());
        assertEquals(JsValue.JsValueType.VECTOR,
                new org.ccg.mpp.simplejs.values.JsVector(new double[]{1, 2}).getType());
        assertEquals(JsValue.JsValueType.DB_DATE_TIME,
                new org.ccg.mpp.simplejs.values.JsDbDateTime(java.time.LocalDateTime.of(2024, 1, 2, 3, 4, 5))
                        .getType());
        assertEquals(JsValue.JsValueType.DB_TIME,
                new org.ccg.mpp.simplejs.values.JsDbTime(java.time.LocalTime.of(3, 4, 5)).getType());
    }

    @Test
    public void test_arguments_and_global_values() {
        final var arguments = new org.ccg.mpp.simplejs.values.JsArguments(List.of(new JsNumber(1)), null, null);
        assertEquals("object", JsCoercion.typeOf(arguments));
        assertEquals("[object Arguments]", JsCoercion.toStrDataOnly(arguments));
        assertEquals(1, arguments.length());
        assertEquals(1, ((JsNumber) arguments.get(0)).getValue());
        assertInstanceOf(JsUndefined.class, arguments.get(3));
        final var global = new org.ccg.mpp.simplejs.values.JsGlobalObject(Environment.global());
        assertEquals("object", JsCoercion.typeOf(global));
        assertEquals("[object global]", JsCoercion.toStrDataOnly(global));
    }

    @Test
    public void test_mapped_arguments_aliasing() {
        final var env = Environment.global();
        env.declareVar("a");
        env.assign("a", new JsNumber(1));
        final var arguments = new org.ccg.mpp.simplejs.values.JsArguments(List.of(new JsNumber(1)), List.of("a"), env);
        arguments.set(0, new JsNumber(9));
        assertEquals(9, ((JsNumber) env.get("a")).getValue());
        env.assign("a", new JsNumber(42));
        assertEquals(42, ((JsNumber) arguments.get(0)).getValue());
    }

    @Test
    public void test_proxy_value() {
        final var objectProxy = new org.ccg.mpp.simplejs.values.JsProxy(new JsObject(), new JsObject());
        assertEquals("object", JsCoercion.typeOf(objectProxy));
        assertEquals("[object Object]", JsCoercion.toStrDataOnly(objectProxy));
        assertFalse(objectProxy.isCallable());
        final var fnProxy = new org.ccg.mpp.simplejs.values.JsProxy(
                new JsNativeFunction("id", (_, _) -> JsUndefined.getInstance()), new JsObject());
        assertEquals("function", JsCoercion.typeOf(fnProxy));
        assertTrue(fnProxy.isCallable());
        assertSame(JsValue.JsValueType.FUNCTION, fnProxy.getTarget().getType());
        assertInstanceOf(JsObject.class, fnProxy.getHandler());
    }

    @Test
    public void test_map_set_date_values() {
        final var map = new org.ccg.mpp.simplejs.values.JsMap();
        assertEquals("object", JsCoercion.typeOf(map));
        assertEquals("[object Map]", JsCoercion.toStrDataOnly(map));
        final var set = new org.ccg.mpp.simplejs.values.JsSet();
        assertEquals("object", JsCoercion.typeOf(set));
        assertEquals("[object Set]", JsCoercion.toStrDataOnly(set));
        final var date = new org.ccg.mpp.simplejs.values.JsDate(0);
        assertEquals("object", JsCoercion.typeOf(date));
        assertEquals(0, JsCoercion.toNumber(date));
        assertEquals("1970-01-01T00:00:00.000Z", date.toISOString());
        final var invalid = new org.ccg.mpp.simplejs.values.JsDate(Double.NaN);
        assertFalse(invalid.isValid());
        assertNull(invalid.toISOString());
        assertEquals("Invalid Date", invalid.toDateString());
    }

    @Test
    public void test_promise_and_generator_values() {
        final var promise = new JsPromise(new EventLoop());
        assertEquals("object", JsCoercion.typeOf(promise));
        assertEquals("[object Promise]", JsCoercion.toStrDataOnly(promise));
        final var generator = new JsGenerator(new Coroutine());
        assertEquals("object", JsCoercion.typeOf(generator));
        assertEquals("[object Generator]", JsCoercion.toStrDataOnly(generator));
    }

    @Test
    public void test_class_value() {
        final var cls = new JsClass("Widget", null, Environment.global());
        assertEquals("function", JsCoercion.typeOf(cls));
        assertEquals("function Widget() { [native code] }", JsCoercion.toStrDataOnly(cls));
        assertEquals("Widget", cls.getName());
    }

    @Test
    public void test_object_class_and_private_fields() {
        final var cls = new JsClass("A", null, Environment.global());
        final var object = new JsObject();
        object.setKlass(cls);
        assertSame(cls, object.getKlass());
        final var x = cls.declarePrivateName("x");
        assertFalse(object.hasPrivate(x));
        object.setPrivate(x, new JsNumber(7));
        assertTrue(object.hasPrivate(x));
        assertEquals(7, ((JsNumber) Objects.requireNonNull(object.getPrivate(x))).getValue());
    }

    @Test
    public void test_function_values() {
        final var function = new JsFunction("f", List.of(), null, true, true, false, false, Environment.global());
        assertEquals("f", function.getName());
        assertTrue(function.isArrow());
        assertTrue(function.isExpressionBody());
        final var native1 = new JsNativeFunction("sum",
                (_, args) -> new JsNumber(((JsNumber) args.getFirst()).getValue() + 1));
        assertEquals("sum", native1.getName());
        assertEquals(2, ((JsNumber) native1.invoke(JsUndefined.getInstance(), List.of(new JsNumber(1)))).getValue());
    }

    @Test
    public void test_singletons_identity() {
        assertSame(JsBoolean.TRUE, JsBoolean.of(true));
        assertSame(JsBoolean.FALSE, JsBoolean.of(false));
    }

    @Test
    public void test_primitive_getters() {
        assertEquals(3.5, new JsNumber(3.5).getValue());
        assertEquals("hi", new JsString("hi").getValue());
        assertTrue(JsBoolean.TRUE.getValue());
        assertFalse(JsBoolean.FALSE.getValue());
        assertEquals(BigInteger.TEN, new JsBigInt(BigInteger.TEN).getValue());
    }

    @Test
    public void test_object_property_operations() {
        final var object = new JsObject();
        assertInstanceOf(JsUndefined.class, object.get("missing"));
        object.set("a", new JsNumber(1));
        assertTrue(object.has("a"));
        assertEquals(1, ((JsNumber) object.get("a")).getValue());
        assertTrue(object.delete("a"));
        assertFalse(object.has("a"));
        assertTrue(object.keys().isEmpty());
    }

    @Test
    public void test_array_operations() {
        final var array = new JsArray(List.of(new JsNumber(1), new JsNumber(2)));
        assertEquals(2, array.length());
        assertEquals(1, ((JsNumber) array.get(0)).getValue());
        assertInstanceOf(JsUndefined.class, array.get(5));
        assertInstanceOf(JsUndefined.class, array.get(-1));
        array.push(new JsNumber(3));
        assertEquals(3, array.length());
        array.set(5, new JsNumber(9));
        assertEquals(6, array.length());
        assertInstanceOf(JsUndefined.class, array.get(4));
        assertEquals(9, ((JsNumber) array.get(5)).getValue());
    }

    @Test
    public void test_array_own_properties() {
        final var array = new JsArray();
        assertFalse(array.hasProperty("raw"));
        assertNull(array.getProperty("raw"));
        array.setProperty("raw", new JsString("x"));
        assertTrue(array.hasProperty("raw"));
        assertEquals("x", ((JsString) Objects.requireNonNull(array.getProperty("raw"))).getValue());
    }

    @Test
    public void test_array_freeze() {
        final var array = new JsArray(List.of(new JsNumber(1)));
        array.freeze();
        assertTrue(array.isFrozen());
        assertFalse(array.set(0, new JsNumber(2)));
        assertFalse(array.push(new JsNumber(3)));
        assertFalse(array.setProperty("raw", new JsString("x")));
        assertFalse(array.setLength(0));
        assertEquals(1, array.length());
        assertEquals(1, ((JsNumber) array.get(0)).getValue());
        assertFalse(array.hasProperty("raw"));
    }

    @Test
    public void test_array_seal_and_prevent_extensions() {
        final var sealed = new JsArray(List.of(new JsNumber(1)));
        sealed.seal();
        assertTrue(sealed.isSealed());
        assertFalse(sealed.isFrozen());
        assertFalse(sealed.isExtensible());
        assertTrue(sealed.set(0, new JsNumber(2)));
        assertFalse(sealed.set(1, new JsNumber(3)));
        assertFalse(sealed.setLength(5));

        final var empty = new JsArray();
        empty.preventExtensions();
        assertTrue(empty.isFrozen());
        assertTrue(empty.isSealed());
        assertFalse(empty.push(new JsNumber(1)));
    }

    @Test
    public void test_object_set_reports_refusal() {
        final var object = new JsObject();
        assertTrue(object.set("a", new JsNumber(1)));
        object.setFlags("a", new JsObject.PropertyFlags(false, true, true));
        assertFalse(object.set("a", new JsNumber(2)));
        assertEquals(1, ((JsNumber) object.get("a")).getValue());

        object.preventExtensions();
        assertFalse(object.set("b", new JsNumber(1)));
        assertFalse(object.setSymbol(new JsSymbol("s"), new JsNumber(1)));
    }

    @Test
    public void test_object_keys_ordering() {
        final var object = new JsObject();
        object.set("b", new JsNumber(1));
        object.set("10", new JsNumber(2));
        object.set("a", new JsNumber(3));
        object.set("2", new JsNumber(4));
        object.set("01", new JsNumber(5));
        assertEquals(List.of("2", "10", "b", "a", "01"), List.copyOf(object.keys()));
    }

    // deleteOwnProperty of a lazily-materialised metadata property (a builtin constructor's
    // non-configurable "prototype") must materialise it first, the same way defineOwnProperty
    // already does, so the delete consults the real flags instead of finding the table empty and
    // trivially succeeding.
    @Test
    public void test_delete_own_property_materialises_metadata_first() {
        final var ctor = new JsNativeFunction("Sample", (_, _) -> JsUndefined.getInstance());
        ctor.markConstructor();
        ctor.setPrototype(new JsObject());
        assertFalse(ctor.deleteOwnProperty(new JsString("prototype")));
        assertTrue(ctor.hasOwnKey(new JsString("prototype")));
    }

    @Test
    public void test_delete_own_property_removes_an_ordinary_property() {
        final var fn = new JsNativeFunction("Sample", (_, _) -> JsUndefined.getInstance());
        fn.setEnumerableProperty("tag", new JsString("x"));
        assertTrue(fn.hasOwnKey(new JsString("tag")));
        assertTrue(fn.deleteOwnProperty(new JsString("tag")));
        assertFalse(fn.hasOwnKey(new JsString("tag")));
    }
}

package org.ccg.mpp.simplejs.internal.interpreter;

import static org.ccg.mpp.simplejs.values.JsLimits.MAX_ARRAY_LENGTH;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.simplejs.builtins.ErrorBuiltins;
import org.ccg.mpp.simplejs.builtins.InterpreterOps;
import org.ccg.mpp.simplejs.builtins.Intrinsics;
import org.ccg.mpp.simplejs.builtins.IterableToList;
import org.ccg.mpp.simplejs.builtins.TypedArrayBuiltins;
import org.ccg.mpp.simplejs.exceptions.JsThrowException;
import org.ccg.mpp.simplejs.exceptions.RangeErrorException;
import org.ccg.mpp.simplejs.exceptions.ReferenceErrorException;
import org.ccg.mpp.simplejs.exceptions.TypeErrorException;
import org.ccg.mpp.simplejs.exceptions.UnsupportedNodeException;
import org.ccg.mpp.simplejs.internal.Coroutine;
import org.ccg.mpp.simplejs.internal.JsCoercion;
import org.ccg.mpp.simplejs.nodes.ArrayPattern;
import org.ccg.mpp.simplejs.nodes.ArrowFunctionExpression;
import org.ccg.mpp.simplejs.nodes.AssignmentPattern;
import org.ccg.mpp.simplejs.nodes.ClassExpression;
import org.ccg.mpp.simplejs.nodes.Expression;
import org.ccg.mpp.simplejs.nodes.FunctionExpression;
import org.ccg.mpp.simplejs.nodes.Identifier;
import org.ccg.mpp.simplejs.nodes.JsNode;
import org.ccg.mpp.simplejs.nodes.NumberLiteral;
import org.ccg.mpp.simplejs.nodes.ObjectPattern;
import org.ccg.mpp.simplejs.nodes.Property;
import org.ccg.mpp.simplejs.nodes.RestElement;
import org.ccg.mpp.simplejs.nodes.StringLiteral;
import org.ccg.mpp.simplejs.values.JsArguments;
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
import org.ccg.mpp.simplejs.values.JsProxy;
import org.ccg.mpp.simplejs.values.JsString;
import org.ccg.mpp.simplejs.values.JsSymbol;
import org.ccg.mpp.simplejs.values.JsTypedArray;
import org.ccg.mpp.simplejs.values.JsUndefined;
import org.ccg.mpp.simplejs.values.JsValue;

public final class InterpreterUtils {
    public static final Set<String> LOGICAL_ASSIGN = Set.of("&&=", "||=", "??=");
    public static final Set<String> LEXICAL_KINDS = Set.of("let", "const");
    public static final Set<String> USING_KINDS = Set.of("using", "await using");

    private InterpreterUtils() {
    }

    public static boolean isNullish(JsValue value) {
        return value instanceof JsNull || value instanceof JsUndefined;
    }

    public static boolean isCallable(JsValue value) {
        return value instanceof JsFunction || value instanceof JsNativeFunction;
    }

    public static boolean isAnonymousFunctionDefinition(JsNode node) {
        return switch (node) {
            case ArrowFunctionExpression ignored -> true;
            case FunctionExpression function -> function.getName() == null;
            case ClassExpression classExpression -> classExpression.getId() == null;
            case null, default -> false;
        };
    }

    public static void setFunctionName(JsValue value, String name) {
        switch (value) {
            case JsFunction function -> function.setInferredName(name);
            case JsClass classValue -> classValue.setInferredName(name);
            default -> {
            }
        }
    }

    public static void applyInferredName(JsNode source, JsValue value, String name) {
        if (name != null && isAnonymousFunctionDefinition(source)) {
            setFunctionName(value, name);
        }
    }

    public static boolean isConstructor(JsValue value) {
        return switch (value) {
            case JsProxy proxy -> proxy.isConstructor();
            case JsClass ignored -> true;
            case JsNativeFunction nativeFunction -> nativeFunction.isConstructor();
            case JsFunction function -> function.isConstructor();
            default -> false;
        };
    }

    public static boolean isObjectLike(JsValue value) {
        return !(value instanceof JsUndefined || value instanceof JsNull || value instanceof JsBoolean
                || value instanceof JsNumber || value instanceof JsString || value instanceof JsBigInt
                || value instanceof JsSymbol);
    }

    public static Integer arrayIndex(String key) {
        if (key.isEmpty()) {
            return null;
        }
        for (var i = 0; i < key.length(); i++) {
            if (!Character.isDigit(key.charAt(i))) {
                return null;
            }
        }
        if (key.length() > 1 && key.charAt(0) == '0') {
            return null;
        }
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static Long canonicalArrayIndexWide(String key) {
        if (key.isEmpty()) {
            return null;
        }
        for (var i = 0; i < key.length(); i++) {
            if (!Character.isDigit(key.charAt(i))) {
                return null;
            }
        }
        if (key.length() > 1 && key.charAt(0) == '0') {
            return null;
        }
        try {
            final var value = Long.parseLong(key);
            return value >= 0 && value < MAX_ARRAY_LENGTH ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static boolean isCanonicalNumericIndexString(String key) {
        if ("-0".equals(key)) {
            return true;
        }
        final double parsed;
        try {
            parsed = Double.parseDouble(key);
        } catch (NumberFormatException ignored) {
            return false;
        }
        return org.ccg.mpp.ejson.internal.NumberFormatter.toJsString(parsed).equals(key);
    }

    public static JsValue numericOld(JsValue oldValue, InterpreterOps ops) {
        if (oldValue instanceof JsBigInt || oldValue instanceof JsNumber) {
            return oldValue;
        }
        return new JsNumber(JsCoercion.toNumber(oldValue, ops));
    }

    public static boolean shouldNotApplyLogical(String operator, JsValue current) {
        return !switch (operator) {
            case "&&=" -> JsCoercion.toBoolean(current);
            case "||=" -> !JsCoercion.toBoolean(current);
            case "??=" -> isNullish(current);
            default -> throw new TypeErrorException("Unknown logical assignment: " + operator);
        };
    }

    public static String labelName(Identifier label) {
        return label == null ? null : label.getName();
    }

    public static boolean matchesLabel(String completionLabel, String loopLabel) {
        return completionLabel == null || completionLabel.equals(loopLabel);
    }

    public static JsValue orUndefined(JsValue value) {
        return value == null ? JsUndefined.getInstance() : value;
    }

    public static String staticKeyName(Expression key) {
        return switch (key.getType()) {
            case IDENTIFIER -> ((Identifier) key).getName();
            case STRING_LITERAL -> ((StringLiteral) key).getValue();
            case NUMBER_LITERAL ->
                JsCoercion.toStrDataOnly(new JsNumber(((NumberLiteral) key).getValue().doubleValue()));
            default -> throw new UnsupportedNodeException(key.getType().name());
        };
    }

    public static JsValue ownValue(JsObject object, String key, InterpreterOps ops) {
        if (ops != null && object.hasAccessor(key)) {
            return ops.getMember(object, new JsString(key));
        }
        return object.get(key);
    }

    public static List<JsValue> arrayLikeElements(JsValue value) {
        return primitiveArrayLikeElements(value, null);
    }

    private static List<JsValue> primitiveArrayLikeElements(JsValue value, InterpreterOps ops) {
        if (value instanceof JsArray array) {
            return array.getElements();
        }
        if (value instanceof JsArguments arguments) {
            return arguments.snapshot();
        }
        if (value instanceof JsTypedArray typed) {
            return TypedArrayBuiltins.elements(typed, ops);
        }
        if (value instanceof JsString string) {
            final var chars = new ArrayList<JsValue>();
            for (var i = 0; i < string.getValue().length(); i++) {
                chars.add(new JsString(String.valueOf(string.getValue().charAt(i))));
            }
            return chars;
        }
        throw new TypeErrorException(JsCoercion.describe(value) + " is not iterable");
    }

    public static List<JsValue> arrayLikeElements(JsValue value, InterpreterOps ops, boolean fromEnd) {
        if (ops == null || !(value instanceof JsObject || value instanceof JsProxy)) {
            return primitiveArrayLikeElements(value, ops);
        }
        final var length = toLength(ops.getMember(value, new JsString("length")), ops);
        InterpreterOps.chargeElements(ops, length);
        final var elements = new ArrayList<JsValue>(length);
        for (var i = 0; i < length; i++) {
            InterpreterOps.pace(ops, i);
            final var index = fromEnd ? length - 1 - i : i;
            final var key = new JsString(Integer.toString(index));
            final var element = ops.getMember(value, key);
            elements.add(element instanceof JsUndefined && !ops.has(value, key) ? JsUndefined.getHole() : element);
        }
        if (fromEnd) {
            Collections.reverse(elements);
        }
        return elements;
    }

    public static List<JsValue> arrayLikeOrIterableToList(JsValue source, IterableToList iterableToList,
            InterpreterOps ops) {
        if (source instanceof JsGenerator || source instanceof JsArguments
                || isCallable(ops.getMember(source, JsSymbol.ITERATOR))) {
            return iterableToList.drain(source);
        }
        return arrayLikeElements(source, ops, false);
    }

    private static int toLength(JsValue value, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || number <= 0) {
            return 0;
        }
        if (number > Integer.MAX_VALUE) {
            throw new TypeErrorException("Array-like receiver length exceeds the supported maximum");
        }
        return (int) number;
    }

    public static List<JsValue> stringCodePoints(String value) {
        final var points = new ArrayList<JsValue>();
        var i = 0;
        while (i < value.length()) {
            final var point = value.codePointAt(i);
            final var width = Character.charCount(point);
            points.add(new JsString(value.substring(i, i + width)));
            i += width;
        }
        return points;
    }

    public static List<JsValue> iterableElements(JsValue value) {
        if (value instanceof JsString string) {
            return stringCodePoints(string.getValue());
        }
        return arrayLikeElements(value);
    }

    public static void collectBoundNames(JsNode target, List<String> names) {
        switch (target) {
            case Identifier id -> names.add(id.getName());
            case AssignmentPattern pattern -> collectBoundNames(pattern.getLeft(), names);
            case RestElement rest -> collectBoundNames(rest.getArgument(), names);
            case ArrayPattern pattern -> {
                for (final var element : pattern.getElements()) {
                    if (element != null) {
                        collectBoundNames(element, names);
                    }
                }
            }
            case ObjectPattern pattern -> {
                for (final var member : pattern.getProperties()) {
                    if (member instanceof RestElement rest) {
                        collectBoundNames(rest.getArgument(), names);
                    } else {
                        collectBoundNames(((Property) member).getValue(), names);
                    }
                }
            }
            default -> {
            }
        }
    }

    public static boolean arrayHasMember(JsArray array, String key) {
        if ("length".equals(key)) {
            return true;
        }
        final var index = arrayIndex(key);
        return index != null && index < array.length() && !array.isHole(index);
    }

    public static boolean deleteArrayElement(JsArray array, String key) {
        return array.deleteOwnProperty(new JsString(key));
    }

    public static boolean protoOwnsKey(JsValue proto, String key) {
        return proto instanceof JsObject object
                ? object.has(key) || object.hasAccessor(key)
                : proto.hasOwnKey(new JsString(key));
    }

    public static boolean protoOwnsSymbol(JsValue proto, JsSymbol symbol) {
        return proto instanceof JsObject object ? object.hasSymbol(symbol) : proto.hasOwnKey(symbol);
    }

    public static TypeErrorException cannotReadProperties(JsValue target, String key) {
        return new TypeErrorException(
                "Cannot read properties of " + JsCoercion.describe(target) + " (reading '" + key + "')");
    }

    public static JsValue stepResult(Coroutine.StepResult step) {
        if (step.value() instanceof YieldDelegation.PassThrough passThrough) {
            return passThrough.result();
        }
        return stepResult(step.value(), step.done());
    }

    public static JsValue stepResult(JsValue value, boolean done) {
        final var result = new JsObject();
        result.set("value", value);
        result.set("done", JsBoolean.of(done));
        return result;
    }

    public static JsValue toErrorValue(RuntimeException error, Intrinsics intrinsics) {
        if (error instanceof JsThrowException thrown) {
            return thrown.getValue();
        }
        final var name = switch (error) {
            case TypeErrorException ignored -> "TypeError";
            case ReferenceErrorException ignored -> "ReferenceError";
            case RangeErrorException ignored -> "RangeError";
            default -> "SyntaxError";
        };
        return intrinsics == null
                ? ErrorBuiltins.makeError(name, error.getMessage())
                : intrinsics.makeError(name, error.getMessage());
    }
}

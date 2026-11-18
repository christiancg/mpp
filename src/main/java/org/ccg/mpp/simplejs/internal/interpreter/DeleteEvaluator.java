package org.ccg.mpp.simplejs.internal.interpreter;

import static org.ccg.mpp.simplejs.internal.interpreter.InterpreterUtils.deleteArrayElement;
import static org.ccg.mpp.simplejs.internal.interpreter.InterpreterUtils.isNullish;
import static org.ccg.mpp.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;

import org.ccg.mpp.simplejs.exceptions.ReferenceErrorException;
import org.ccg.mpp.simplejs.exceptions.TypeErrorException;
import org.ccg.mpp.simplejs.internal.Environment;
import org.ccg.mpp.simplejs.internal.Interpreter;
import org.ccg.mpp.simplejs.internal.JsCoercion;
import org.ccg.mpp.simplejs.nodes.Expression;
import org.ccg.mpp.simplejs.nodes.MemberExpression;
import org.ccg.mpp.simplejs.nodes.SuperExpression;
import org.ccg.mpp.simplejs.values.JsArguments;
import org.ccg.mpp.simplejs.values.JsArray;
import org.ccg.mpp.simplejs.values.JsBoolean;
import org.ccg.mpp.simplejs.values.JsCallableProperties;
import org.ccg.mpp.simplejs.values.JsClass;
import org.ccg.mpp.simplejs.values.JsFunction;
import org.ccg.mpp.simplejs.values.JsNativeFunction;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsProxy;
import org.ccg.mpp.simplejs.values.JsString;
import org.ccg.mpp.simplejs.values.JsSymbol;
import org.ccg.mpp.simplejs.values.JsValue;

final class DeleteEvaluator {
    private final Interpreter interp;
    private final ProxyDispatch proxies;

    DeleteEvaluator(Interpreter interp, ProxyDispatch proxies) {
        this.interp = interp;
        this.proxies = proxies;
    }

    JsValue evalDelete(Expression argument, Environment env) {
        if (!(argument instanceof MemberExpression member)) {
            interp.eval(argument, env);
            return JsBoolean.TRUE;
        }
        if (member.getObject() instanceof SuperExpression) {
            env.resolveThis();
            interp.memberKeyValue(member, env);
            throw new ReferenceErrorException("Unsupported reference to 'super'");
        }
        final var target = interp.eval(member.getObject(), env);
        final var keyValue = interp.memberKeyValue(member, env);
        if (isNullish(target)) {
            if (member.isOptional()) {
                return JsBoolean.TRUE;
            }
            throw new TypeErrorException(
                    "Cannot convert undefined or null to object in a delete of " + JsCoercion.describe(keyValue));
        }
        if (keyValue instanceof JsSymbol symbol) {
            return deleteSymbolMember(target, symbol);
        }
        final var key = JsCoercion.toStr(keyValue, interp.ops());
        return switch (target) {
            case JsProxy proxy -> {
                if (!proxies.delete(proxy, new JsString(key))) {
                    throw new TypeErrorException("Cannot delete property '" + key + "' of #<Object>");
                }
                yield JsBoolean.TRUE;
            }
            case JsObject object -> {
                if (!object.delete(key)) {
                    throw new TypeErrorException("Cannot delete property '" + key + "' of #<Object>");
                }
                yield JsBoolean.TRUE;
            }
            case JsClass cls -> {
                if (!cls.getStaticOwner().delete(key)) {
                    throw new TypeErrorException("Cannot delete property '" + key + "' of #<Object>");
                }
                yield JsBoolean.TRUE;
            }
            case JsArray array -> {
                if (!deleteArrayElement(array, key)) {
                    throw new TypeErrorException("Cannot delete property '" + key + "' of #<Array>");
                }
                yield JsBoolean.TRUE;
            }
            case JsArguments arguments -> {
                if (!arguments.deleteOwnProperty(new JsString(key))) {
                    throw new TypeErrorException("Cannot delete property '" + key + "' of #<Object>");
                }
                yield JsBoolean.TRUE;
            }
            case JsCallableProperties callable -> {
                if (("name".equals(key) || "length".equals(key)) && !callable.hasProperty(key)) {
                    callable.markMetadataDeleted(key);
                    yield JsBoolean.TRUE;
                }
                if ("prototype".equals(key) && !callable.hasProperty(key) && hasOwnPrototypeMetadata(callable)) {
                    throw new TypeErrorException("Cannot delete property 'prototype' of #<Function>");
                }
                yield JsBoolean.of(callable.deleteProperty(key));
            }
            default -> {
                if (!isObjectLike(target)) {
                    yield JsBoolean.TRUE;
                }
                if (!target.deleteOwnProperty(new JsString(key))) {
                    throw new TypeErrorException("Cannot delete property '" + key + "' of #<Object>");
                }
                yield JsBoolean.TRUE;
            }
        };
    }

    static boolean hasOwnPrototypeMetadata(JsCallableProperties callable) {
        if (callable instanceof JsFunction function) {
            return function.isConstructor() || function.isGenerator();
        }
        return callable instanceof JsNativeFunction nativeFunction && nativeFunction.getPrototype() != null;
    }

    JsValue deleteSymbolMember(JsValue target, JsSymbol symbol) {
        return switch (target) {
            case JsProxy proxy -> JsBoolean.of(proxies.delete(proxy, symbol));
            case JsObject object -> {
                if (object.isNotDeleteSymbol(symbol)) {
                    throw new TypeErrorException(
                            "Cannot delete property '" + symbol.getDescription() + "' of #<Object>");
                }
                yield JsBoolean.TRUE;
            }
            case JsClass cls -> {
                if (cls.getStaticOwner().isNotDeleteSymbol(symbol)) {
                    throw new TypeErrorException(
                            "Cannot delete property '" + symbol.getDescription() + "' of #<Object>");
                }
                yield JsBoolean.TRUE;
            }
            default -> JsBoolean.of(target.deleteOwnProperty(symbol));
        };
    }
}

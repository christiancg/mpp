package org.ccg.mpp.simplejs.internal.interpreter;

import java.util.Locale;
import java.util.function.Supplier;
import org.ccg.mpp.simplejs.builtins.GlobalScope;
import org.ccg.mpp.simplejs.exceptions.JsThrowException;
import org.ccg.mpp.simplejs.exceptions.ScriptAbortException;
import org.ccg.mpp.simplejs.exceptions.ScriptLimitException;
import org.ccg.mpp.simplejs.exceptions.SyntaxErrorException;
import org.ccg.mpp.simplejs.internal.Completion;
import org.ccg.mpp.simplejs.internal.Coroutine;
import org.ccg.mpp.simplejs.internal.Environment;
import org.ccg.mpp.simplejs.internal.Interpreter;
import org.ccg.mpp.simplejs.internal.JsCoercion;
import org.ccg.mpp.simplejs.internal.ModuleResult;
import org.ccg.mpp.simplejs.nodes.ExportAllDeclaration;
import org.ccg.mpp.simplejs.nodes.ExportDefaultDeclaration;
import org.ccg.mpp.simplejs.nodes.ExportNamedDeclaration;
import org.ccg.mpp.simplejs.nodes.ImportDeclaration;
import org.ccg.mpp.simplejs.nodes.Program;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsPromise;
import org.ccg.mpp.simplejs.values.JsUndefined;
import org.ccg.mpp.simplejs.values.JsValue;

public record ModuleLifecycle(Interpreter interp) {
    public Interpreter.ProgramOutcome evaluateTopLevelModule(Program program) {
        final var env = Environment.global();
        final var globalThis = GlobalScope.install(env, interp.eventLoop, interp::callValue, interp::iterableToList,
                interp.host.console(), interp.ops, interp.host.network(), interp.host.limits(), interp.intrinsics);
        env.defineThis(globalThis);
        interp.globalObjectValue = globalThis;
        interp.globalEnv = env;
        final var result = new ModuleResult();
        final var coroutine = new Coroutine();
        interp.coroutines.add(coroutine);
        coroutine.markAsync();
        env.defineCoroutine(coroutine);
        interp.functions.runsAs(coroutine, null);
        coroutine.startAsync(() -> {
            if (interp.moduleBodyWrapper == null) {
                evaluateModuleBody(program, env, result);
            } else {
                interp.moduleBodyWrapper.around(() -> evaluateModuleBody(program, env, result));
            }
            return JsUndefined.getInstance();
        });
        markContractPromiseHandled(result);
        interp.eventLoop.drain(interp.deadlineNanos);
        return new Interpreter.ProgramOutcome(result.last, result.hasReturn, result.returnValue, result.exportDefault,
                result.namedExports);
    }

    public void markContractPromiseHandled(ModuleResult result) {
        final var contract = result.hasReturn ? result.returnValue : result.exportDefault;
        if (contract instanceof JsPromise promise) {
            promise.markHandled();
        }
    }

    public void reportUnhandledRejections() {
        if (!interp.host.limits().reportUnhandledRejections()) {
            return;
        }
        final var sink = interp.host.console();
        if (sink == null) {
            return;
        }
        for (final var promise : interp.eventLoop.promises()) {
            if (promise.isUnhandledRejection()) {
                sink.accept("UnhandledPromiseRejection: " + JsCoercion.describe(promise.getResult()));
            }
        }
    }

    public void evaluateModuleBody(Program program, Environment env, ModuleResult result) {
        for (final var statement : program.getBody()) {
            if (statement instanceof ImportDeclaration importDeclaration) {
                interp.modules.bindImport(importDeclaration, env);
            }
        }
        VarHoisting.hoistVars(program.getBody(), env);
        interp.hoist(program.getBody(), env);
        RuntimeException pending = null;
        try {
            runModuleStatements(program, env, result);
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException error) {
            pending = error;
        }
        interp.statements.disposeScope(env, Completion.empty(), pending);
    }

    public JsValue importModule(String moduleId, String displayName, Supplier<Program> parser, Coroutine coroutine) {
        final var state = interp.moduleRegistry.stateOf(moduleId);
        if (state == ModuleRegistry.State.EVALUATED) {
            return interp.moduleRegistry.namespaceOf(moduleId);
        }
        if (state == ModuleRegistry.State.FAILED) {
            throw interp.moduleRegistry.failureOf(moduleId);
        }
        if (state == ModuleRegistry.State.EVALUATING) {
            throw new JsThrowException(
                    interp.intrinsics.makeError("Error", "Circular import of module '" + moduleId + "'"));
        }
        if (interp.maxModuleDepth >= 0 && interp.moduleDepth >= interp.maxModuleDepth) {
            throw new ScriptLimitException("Script exceeded its maximum module nesting depth");
        }
        interp.moduleRegistry.beginEvaluation(moduleId);
        interp.moduleDepth++;
        final var previousModule = interp.callStack.enterModule(displayName);
        try {
            final var namespace = coroutine == null || !coroutine.isAsync()
                    ? evaluateDetached(parser.get(), displayName)
                    : evaluateModule(parser.get(), coroutine);
            interp.moduleRegistry.complete(moduleId, namespace);
            return namespace;
        } catch (RuntimeException error) {
            interp.moduleRegistry.fail(moduleId, error);
            throw error;
        } finally {
            interp.callStack.exitModule(previousModule);
            interp.moduleDepth--;
        }
    }

    public JsValue cacheBuiltinModule(String moduleId, Supplier<JsValue> factory) {
        final var state = interp.moduleRegistry.stateOf(moduleId);
        if (state == ModuleRegistry.State.EVALUATED) {
            return interp.moduleRegistry.namespaceOf(moduleId);
        }
        final var module = factory.get();
        interp.moduleRegistry.complete(moduleId, module);
        return module;
    }

    private JsValue evaluateDetached(Program program, String displayName) {
        final var coroutine = new Coroutine();
        interp.registerCoroutine(coroutine);
        coroutine.markAsync();
        interp.functions.runsAs(coroutine, interp.callStack.segmentFor(null, displayName));
        final var evaluation = new DetachedEvaluation();
        coroutine.startAsync(() -> {
            try {
                evaluation.namespace = evaluateModule(program, coroutine);
            } catch (RuntimeException failure) {
                evaluation.failure = failure;
            }
            return JsUndefined.getInstance();
        });
        interp.eventLoop.drainUntil(coroutine::isDone, interp.deadlineNanos);
        if (evaluation.failure != null) {
            throw evaluation.failure;
        }
        if (!coroutine.isDone()) {
            throw new JsThrowException(interp.intrinsics.makeError("Error",
                    "Module '" + displayName + "' awaited something that never settled"));
        }
        return evaluation.namespace;
    }

    private static final class DetachedEvaluation {
        private JsValue namespace;
        private RuntimeException failure;
    }

    public JsValue evaluateModule(Program program, Coroutine coroutine) {
        final var env = interp.globalEnv.functionChild();
        env.defineCoroutine(coroutine);
        final var result = new ModuleResult();
        evaluateModuleBody(program, env, result);
        final var namespace = new JsObject();
        result.namedExports.forEach(namespace::set);
        namespace.set("default", result.exportDefault == null ? JsUndefined.getInstance() : result.exportDefault);
        return namespace;
    }

    public void runModuleStatements(Program program, Environment env, ModuleResult result) {
        moduleLoop : for (final var statement : program.getBody()) {
            switch (statement) {
                case ImportDeclaration ignored -> {
                }
                case ExportDefaultDeclaration exportDefaultDeclaration ->
                    result.exportDefault = interp.modules.evalExportDefault(exportDefaultDeclaration, env);
                case ExportNamedDeclaration exportNamedDeclaration ->
                    interp.modules.evalExportNamed(exportNamedDeclaration, env, result.namedExports);
                case ExportAllDeclaration exportAllDeclaration ->
                    interp.modules.evalExportAll(exportAllDeclaration, env, result.namedExports);
                default -> {
                    final var completion = interp.evalStatement(statement, env);
                    if (completion.kind() == Completion.Kind.RETURN) {
                        result.hasReturn = true;
                        result.returnValue = completion.value();
                        break moduleLoop;
                    }
                    if (!completion.isNormal()) {
                        throw new SyntaxErrorException(
                                "Illegal " + completion.kind().name().toLowerCase(Locale.ROOT) + " statement");
                    }
                    result.last = completion.value();
                }
            }
        }
    }

}

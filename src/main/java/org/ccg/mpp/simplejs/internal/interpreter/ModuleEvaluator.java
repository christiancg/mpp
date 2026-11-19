package org.ccg.mpp.simplejs.internal.interpreter;

import static org.ccg.mpp.simplejs.internal.interpreter.InterpreterUtils.collectBoundNames;

import java.util.ArrayList;
import java.util.Map;
import java.util.function.Supplier;
import org.ccg.mpp.simplejs.ModuleReferences;
import org.ccg.mpp.simplejs.builtins.DbModule;
import org.ccg.mpp.simplejs.builtins.ScriptModule;
import org.ccg.mpp.simplejs.exceptions.JsThrowException;
import org.ccg.mpp.simplejs.exceptions.SyntaxErrorException;
import org.ccg.mpp.simplejs.exceptions.UnexpectedCharacterException;
import org.ccg.mpp.simplejs.exceptions.UnexpectedEndOfInputException;
import org.ccg.mpp.simplejs.exceptions.UnexpectedTokenException;
import org.ccg.mpp.simplejs.exceptions.UnsupportedNodeException;
import org.ccg.mpp.simplejs.exceptions.UnterminatedCommentException;
import org.ccg.mpp.simplejs.exceptions.UnterminatedRegexException;
import org.ccg.mpp.simplejs.exceptions.UnterminatedStringException;
import org.ccg.mpp.simplejs.exceptions.UnterminatedTemplateException;
import org.ccg.mpp.simplejs.host.HostBindings;
import org.ccg.mpp.simplejs.internal.Coroutine;
import org.ccg.mpp.simplejs.internal.Environment;
import org.ccg.mpp.simplejs.internal.EventLoop;
import org.ccg.mpp.simplejs.internal.Interpreter;
import org.ccg.mpp.simplejs.internal.JsCoercion;
import org.ccg.mpp.simplejs.internal.Lexer;
import org.ccg.mpp.simplejs.internal.Parser;
import org.ccg.mpp.simplejs.nodes.ClassDeclaration;
import org.ccg.mpp.simplejs.nodes.ExportAllDeclaration;
import org.ccg.mpp.simplejs.nodes.ExportDefaultDeclaration;
import org.ccg.mpp.simplejs.nodes.ExportNamedDeclaration;
import org.ccg.mpp.simplejs.nodes.Expression;
import org.ccg.mpp.simplejs.nodes.FunctionDeclaration;
import org.ccg.mpp.simplejs.nodes.Identifier;
import org.ccg.mpp.simplejs.nodes.ImportDeclaration;
import org.ccg.mpp.simplejs.nodes.ImportDefaultSpecifier;
import org.ccg.mpp.simplejs.nodes.ImportExpression;
import org.ccg.mpp.simplejs.nodes.ImportNamespaceSpecifier;
import org.ccg.mpp.simplejs.nodes.ImportSpecifier;
import org.ccg.mpp.simplejs.nodes.JsNode;
import org.ccg.mpp.simplejs.nodes.Program;
import org.ccg.mpp.simplejs.nodes.Statement;
import org.ccg.mpp.simplejs.nodes.StringLiteral;
import org.ccg.mpp.simplejs.nodes.VariableDeclaration;
import org.ccg.mpp.simplejs.values.EJsonInterop;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsPromise;
import org.ccg.mpp.simplejs.values.JsString;
import org.ccg.mpp.simplejs.values.JsUndefined;
import org.ccg.mpp.simplejs.values.JsValue;

public final class ModuleEvaluator {
    private final Interpreter interp;
    private final ClassEvaluator classes;
    private final HostBindings host;
    private final EventLoop eventLoop;

    public ModuleEvaluator(Interpreter interp, ClassEvaluator classes, HostBindings host, EventLoop eventLoop) {
        this.interp = interp;
        this.classes = classes;
        this.host = host;
        this.eventLoop = eventLoop;
    }

    private record ResolvedBinding(JsValue value, boolean moduleNamespace) {
    }

    private ResolvedBinding resolveModule(String source, Coroutine coroutine) {
        return switch (source) {
            case "args" -> builtin(interp.cacheBuiltinModule("builtin:args",
                    () -> host.args() == null ? new JsObject() : EJsonInterop.fromEjson(host.args())));
            case "db" -> builtin(interp.cacheBuiltinModule("builtin:db", this::createDbModule));
            case ModuleReferences.SCRIPT_MODULE -> builtin(interp.cacheBuiltinModule("builtin:script",
                    () -> ScriptModule.create(this::importText, host.limits(), interp.intrinsics(), interp.ops())));
            default -> new ResolvedBinding(resolveHostModule(source, coroutine), true);
        };
    }

    private ResolvedBinding builtin(JsValue value) {
        return new ResolvedBinding(value, false);
    }

    private JsValue createDbModule() {
        final var project = host.project();
        if (project == null) {
            throw new JsThrowException(interp.intrinsics().makeError("Error", "Project access is not available"));
        }
        project.useIntrinsics(interp.intrinsics());
        return DbModule.create(project, interp.ops(), interp.intrinsics(), host.limits());
    }

    private JsValue resolveHostModule(String source, Coroutine coroutine) {
        final var resolver = host.moduleResolver();
        final var resolved = resolver == null ? null : resolver.resolve(source, "main");
        if (resolved == null) {
            throw new JsThrowException(interp.intrinsics().makeError("Error", "Cannot find module '" + source + "'"));
        }
        if (resolved.sourceHidden()) {
            return interp.importModule(resolved.moduleId(), resolved.displayName(),
                    () -> parseWithoutSourceText(resolved.source()), coroutine);
        }
        final var compiled = resolved.compiled();
        final var reusable = compiled != null && compiled.strictScriptGoal() == host.strictScriptGoal();
        return interp.importModule(resolved.moduleId(), resolved.displayName(),
                () -> reusable ? compiled.program() : parse(resolved.source()), coroutine);
    }

    private JsValue importText(String moduleId, String source, Coroutine coroutine) {
        return interp.importModule(moduleId, moduleId, () -> parse(source), coroutine);
    }

    private Program parse(String source) {
        return parseReportingSyntaxErrors(() -> Parser.parse(Lexer.lexWithPositions(source), host.strictScriptGoal()));
    }

    private Program parseWithoutSourceText(String source) {
        return parseReportingSyntaxErrors(
                () -> Parser.parseWithoutSourceText(Lexer.lexWithPositions(source), host.strictScriptGoal()));
    }

    private Program parseReportingSyntaxErrors(Supplier<Program> parser) {
        try {
            return parser.get();
        } catch (SyntaxErrorException | UnexpectedTokenException | UnexpectedEndOfInputException
                | UnexpectedCharacterException | UnterminatedStringException | UnterminatedTemplateException
                | UnterminatedCommentException | UnterminatedRegexException error) {
            throw new JsThrowException(interp.intrinsics().makeError("SyntaxError", error.getMessage()));
        }
    }

    private JsValue moduleNamespace(String source, Coroutine coroutine) {
        return namespaceObject(resolveModule(source, coroutine));
    }

    private JsValue namespaceObject(ResolvedBinding resolved) {
        if (resolved.moduleNamespace()) {
            return resolved.value();
        }
        final var namespace = new JsObject();
        if (resolved.value() instanceof JsObject object) {
            for (final var key : object.keys()) {
                namespace.set(key, object.get(key));
            }
        }
        namespace.set("default", resolved.value());
        return namespace;
    }

    public JsValue evalImportExpression(ImportExpression expression, Environment env) {
        final var promise = new JsPromise(eventLoop);
        try {
            final var specifier = JsCoercion.toStr(interp.eval(expression.getSource(), env), interp.ops());
            promise.resolve(moduleNamespace(specifier, env.resolveCoroutine()));
        } catch (JsThrowException error) {
            promise.reject(error.getValue());
        }
        return promise;
    }

    public JsValue evalMetaProperty() {
        final var meta = new JsObject();
        meta.set("url", new JsString("simplejs:main"));
        return meta;
    }

    public void bindImport(ImportDeclaration declaration, Environment env) {
        final var resolved = resolveModule(declaration.getSource().getValue(), env.resolveCoroutine());
        final var namespace = resolved.value();
        final var defaultBinding = resolved.moduleNamespace() ? moduleMember(namespace, "default") : namespace;
        for (final var specifier : declaration.getSpecifiers()) {
            switch (specifier) {
                case ImportDefaultSpecifier defaultSpecifier ->
                    defineModuleBinding(env, defaultSpecifier.getLocal().getName(), defaultBinding);
                case ImportNamespaceSpecifier namespaceSpecifier ->
                    defineModuleBinding(env, namespaceSpecifier.getLocal().getName(), namespaceObject(resolved));
                case ImportSpecifier importSpecifier -> defineModuleBinding(env, importSpecifier.getLocal().getName(),
                        moduleMember(namespace, moduleName(importSpecifier.getImported())));
                default -> throw new UnsupportedNodeException(specifier.getType().name());
            }
        }
    }

    public JsValue evalExportDefault(ExportDefaultDeclaration declaration, Environment env) {
        final var value = declaration.getDeclaration();
        if (value instanceof FunctionDeclaration functionDeclaration) {
            final var name = functionDeclaration.getName() == null
                    ? "default"
                    : functionDeclaration.getName().getName();
            return interp.makeFunction(name, functionDeclaration.getParams(), functionDeclaration.getBody(), false,
                    false, functionDeclaration.isAsync(), functionDeclaration.isGenerator(), env,
                    functionDeclaration.getSourceText());
        }
        if (value instanceof ClassDeclaration classDeclaration) {
            classes.evalClassDeclaration(classDeclaration, env);
            return env.get(classDeclaration.getId().getName());
        }
        final var evaluated = interp.eval((Expression) value, env);
        InterpreterUtils.applyInferredName(value, evaluated, "default");
        return evaluated;
    }

    public void evalExportNamed(ExportNamedDeclaration declaration, Environment env, Map<String, JsValue> exports) {
        if (declaration.getDeclaration() instanceof Statement inner) {
            interp.evalStatement(inner, env);
            final var names = new ArrayList<String>();
            collectExportedNames(inner, names);
            for (final var name : names) {
                exports.put(name, env.get(name));
            }
            return;
        }
        final var source = declaration.getSource();
        final var resolved = source == null ? null : resolveModule(source.getValue(), env.resolveCoroutine());
        for (final var specifier : declaration.getSpecifiers()) {
            final var local = moduleName(specifier.getLocal());
            final var value = resolved == null ? env.get(local) : reexportedMember(resolved, local);
            exports.put(moduleName(specifier.getExported()), value);
        }
    }

    private JsValue reexportedMember(ResolvedBinding resolved, String local) {
        if (!resolved.moduleNamespace() && "default".equals(local)) {
            return resolved.value();
        }
        return moduleMember(resolved.value(), local);
    }

    public void evalExportAll(ExportAllDeclaration declaration, Environment env, Map<String, JsValue> exports) {
        final var namespace = resolveModule(declaration.getSource().getValue(), env.resolveCoroutine()).value();
        if (declaration.getExported() != null) {
            exports.put(declaration.getExported().getName(), namespace);
        } else if (namespace instanceof JsObject object) {
            for (final var key : object.keys()) {
                if (!"default".equals(key)) {
                    exports.put(key, object.get(key));
                }
            }
        }
    }

    private void collectExportedNames(Statement declaration, java.util.List<String> names) {
        switch (declaration) {
            case VariableDeclaration variableDeclaration -> {
                for (final var declarator : variableDeclaration.getDeclarations()) {
                    collectBoundNames(declarator.getId(), names);
                }
            }
            case FunctionDeclaration functionDeclaration -> names.add(functionDeclaration.getName().getName());
            case ClassDeclaration classDeclaration -> names.add(classDeclaration.getId().getName());
            default -> {
            }
        }
    }

    private JsValue moduleMember(JsValue namespace, String name) {
        if (namespace instanceof JsObject object) {
            return object.get(name);
        }
        return JsUndefined.getInstance();
    }

    private String moduleName(JsNode node) {
        return switch (node) {
            case Identifier identifier -> identifier.getName();
            case StringLiteral literal -> literal.getValue();
            default -> throw new UnsupportedNodeException(node.getType().name());
        };
    }

    private void defineModuleBinding(Environment env, String name, JsValue value) {
        if (!env.hasLocal(name)) {
            env.declareVar(name);
        }
        env.assign(name, value);
    }
}

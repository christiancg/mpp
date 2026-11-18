package org.ccg.mpp.simplejs.internal.interpreter;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.simplejs.internal.Environment;
import org.ccg.mpp.simplejs.nodes.BlockStatement;
import org.ccg.mpp.simplejs.nodes.DoWhileStatement;
import org.ccg.mpp.simplejs.nodes.ExportNamedDeclaration;
import org.ccg.mpp.simplejs.nodes.ForInStatement;
import org.ccg.mpp.simplejs.nodes.ForOfStatement;
import org.ccg.mpp.simplejs.nodes.ForStatement;
import org.ccg.mpp.simplejs.nodes.IfStatement;
import org.ccg.mpp.simplejs.nodes.JsNode;
import org.ccg.mpp.simplejs.nodes.LabeledStatement;
import org.ccg.mpp.simplejs.nodes.Statement;
import org.ccg.mpp.simplejs.nodes.SwitchStatement;
import org.ccg.mpp.simplejs.nodes.TryStatement;
import org.ccg.mpp.simplejs.nodes.VariableDeclaration;
import org.ccg.mpp.simplejs.nodes.WhileStatement;

public final class VarHoisting {
    private VarHoisting() {
    }

    public static void hoistVars(List<Statement> body, Environment env) {
        for (final var name : varNames(body)) {
            env.declareVar(name);
        }
    }

    public static List<String> varNames(List<Statement> body) {
        final var names = new ArrayList<String>();
        for (final var statement : body) {
            collect(statement, names);
        }
        return names;
    }

    private static void collect(JsNode node, List<String> names) {
        switch (node) {
            case ExportNamedDeclaration export -> collect(export.getDeclaration(), names);
            case VariableDeclaration declaration -> {
                if ("var".equals(declaration.getKind())) {
                    for (final var declarator : declaration.getDeclarations()) {
                        InterpreterUtils.collectBoundNames(declarator.getId(), names);
                    }
                }
            }
            case BlockStatement block -> collectAll(block.getBody(), names);
            case IfStatement statement -> {
                collect(statement.getConsequent(), names);
                collect(statement.getAlternate(), names);
            }
            case WhileStatement statement -> collect(statement.getBody(), names);
            case DoWhileStatement statement -> collect(statement.getBody(), names);
            case ForStatement statement -> {
                collect(statement.getInit(), names);
                collect(statement.getBody(), names);
            }
            case ForInStatement statement -> {
                collect(statement.getLeft(), names);
                collect(statement.getBody(), names);
            }
            case ForOfStatement statement -> {
                collect(statement.getLeft(), names);
                collect(statement.getBody(), names);
            }
            case LabeledStatement statement -> collect(statement.getBody(), names);
            case TryStatement statement -> {
                collect(statement.getBlock(), names);
                if (statement.getHandler() != null) {
                    collect(statement.getHandler().getBody(), names);
                }
                collect(statement.getFinalizer(), names);
            }
            case SwitchStatement statement -> {
                for (final var switchCase : statement.getCases()) {
                    collectAll(switchCase.getConsequent(), names);
                }
            }
            case null, default -> {
            }
        }
    }

    private static void collectAll(List<Statement> body, List<String> names) {
        for (final var statement : body) {
            collect(statement, names);
        }
    }
}

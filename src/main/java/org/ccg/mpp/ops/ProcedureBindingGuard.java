package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.simplejs.ModuleReferences;
import org.ccg.mpp.simplejs.SimpleJs;

public final class ProcedureBindingGuard {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private static final ModuleReferences NO_REFERENCES = new ModuleReferences(Set.of(), false);

    private ProcedureBindingGuard() {
    }

    public static OperationResponse refusal(OperationType type, String projectName, String procedureName,
            String actingUser) throws IOException {
        if (exempt(projectName, actingUser)) {
            return null;
        }
        final var memo = new HashMap<String, ModuleReferences>();
        for (final var boundProcedure : foreignBoundProcedures(projectName, actingUser)) {
            if (reaches(projectName, boundProcedure, procedureName, memo)) {
                return new OperationResponse(type, "Procedure '" + procedureName
                        + "' is bound by a trigger, schedule, entrypoint or middleware that runs as another user; only"
                        + " that user, a project owner or an admin may change it",
                        ErrorCode.PROCEDURE_BOUND_BY_OTHER_DEFINER);
            }
        }
        return null;
    }

    static OperationResponse foreignDefinitionRefusal(OperationType type, String projectName, String definer,
            String actingUser) {
        if (definer == null || definer.equals(actingUser) || exempt(projectName, actingUser)) {
            return null;
        }
        return new OperationResponse(type, ErrorCode.DEFINITION_OWNED_BY_OTHER_DEFINER);
    }

    private static boolean exempt(String projectName, String actingUser) {
        if (actingUser == null) {
            return false;
        }
        final var user = cache.getAdminUserEntry(actingUser);
        if (user != null && user.isAdmin()) {
            return true;
        }
        final var dbEntry = cache.getAdminProjectEntry(projectName);
        return dbEntry != null && dbEntry.isOwner(actingUser);
    }

    private static Set<String> foreignBoundProcedures(String projectName, String actingUser) throws IOException {
        final var bindings = new LinkedHashSet<String>();
        for (final var trigger : TriggerOperationHelper.triggersIn(projectName)) {
            addIfForeign(bindings, trigger.getDefiner(), trigger.getProcedureName(), actingUser);
        }
        for (final var schedule : ScheduleOperationHelper.schedulesIn(projectName)) {
            addIfForeign(bindings, schedule.getDefiner(), schedule.getProcedureName(), actingUser);
        }
        for (final var entrypoint : EntrypointOperationHelper.entrypointsIn(projectName)) {
            addIfForeign(bindings, entrypoint.getDefiner(), entrypoint.getProcedureName(), actingUser);
        }
        for (final var middleware : MiddlewareOperationHelper.middlewaresIn(projectName)) {
            addIfForeign(bindings, middleware.getDefiner(), middleware.getProcedureName(), actingUser);
        }
        return bindings;
    }

    private static void addIfForeign(Set<String> bindings, String definer, String procedureName, String actingUser) {
        if (actingUser == null || !actingUser.equals(definer)) {
            bindings.add(procedureName);
        }
    }

    private static boolean reaches(String projectName, String from, String target, Map<String, ModuleReferences> memo) {
        final var visited = new HashSet<String>();
        final var pending = new ArrayDeque<String>();
        pending.add(from);
        while (!pending.isEmpty()) {
            final var name = pending.poll();
            if (name.equals(target)) {
                return true;
            }
            if (!visited.add(name)) {
                continue;
            }
            final var references = memo.computeIfAbsent(name, key -> referencesOf(projectName, key));
            if (references.opaque()) {
                return true;
            }
            pending.addAll(references.procedureNames());
        }
        return false;
    }

    private static ModuleReferences referencesOf(String projectName, String name) {
        final var definition = cache.getProcedure(projectName, name);
        if (definition == null || definition.getSource() == null) {
            return NO_REFERENCES;
        }
        final var compiled = compiledProcedures.get(projectName, name, definition.getVersion(), definition.getSource());
        return simpleJs.moduleReferences(compiled);
    }
}

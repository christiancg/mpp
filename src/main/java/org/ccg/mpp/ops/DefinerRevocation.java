package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.data.MiddlewareDefinition;
import org.ccg.mpp.data.ScheduleDefinition;
import org.ccg.mpp.data.StoredDefinition;
import org.ccg.mpp.data.TriggerDefinition;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.RestampedRecords;

public final class DefinerRevocation {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final Logger logger = Logger.logFor(DefinerRevocation.class);

    private DefinerRevocation() {
    }

    private interface Disabling {
        boolean run() throws IOException, InterruptedException;
    }

    public static void forDefiner(String username, RestampedRecords restamped) {
        final List<String> projectNames = cache.getAllAdminProjectEntries().stream().map(AdminProjectEntry::get_id)
                .toList();
        for (final var projectName : projectNames) {
            if (!revoke(projectName, username::equals, restamped)) {
                return;
            }
        }
    }

    public static void forProject(String projectName, RestampedRecords restamped) {
        revoke(projectName, _ -> true, restamped);
    }

    private static boolean revoke(String projectName, Predicate<String> definerInScope, RestampedRecords restamped) {
        return revokeTriggers(projectName, definerInScope, restamped)
                && revokeSchedules(projectName, definerInScope, restamped)
                && revokeAll(ENTRYPOINTS, projectName, definerInScope, restamped)
                && revokeAll(MIDDLEWARES, projectName, definerInScope, restamped);
    }

    private static boolean revokeTriggers(String projectName, Predicate<String> definerInScope,
            RestampedRecords restamped) {
        for (final var collName : cache.getCollectionNamesForProject(projectName)) {
            var disabledAny = false;
            for (final var trigger : List.copyOf(cache.getTriggersFor(projectName, collName))) {
                if (trigger.isBefore() || outOfScope(trigger.isEnabled(), trigger.getDefiner(), definerInScope)) {
                    continue;
                }
                final var refusal = DefinerAuthority.refusal(trigger.getDefiner(), projectName, collName);
                if (refusal == null) {
                    continue;
                }
                final var outcome = disable(describe(projectName, collName, trigger), refusal,
                        () -> TriggerOperationHelper.disable(projectName, collName, trigger, refusal));
                if (outcome == Outcome.INTERRUPTED) {
                    return false;
                }
                disabledAny |= outcome == Outcome.DISABLED;
            }
            if (disabledAny) {
                restamped.add(AdminRecordKey.triggers(projectName, collName));
            }
        }
        return true;
    }

    private static boolean revokeSchedules(String projectName, Predicate<String> definerInScope,
            RestampedRecords restamped) {
        final List<ScheduleDefinition> schedules;
        try {
            schedules = ScheduleOperationHelper.schedulesIn(projectName);
        } catch (IOException e) {
            logger.error("Could not read the schedules of project '" + projectName + "' to re-check their definers; a"
                    + " schedule whose definer lost its rights is disabled when it next fires", e);
            return true;
        }
        for (final var schedule : schedules) {
            if (outOfScope(schedule.isEnabled(), schedule.getDefiner(), definerInScope)) {
                continue;
            }
            final var refusal = DefinerAuthority.refusal(schedule.getDefiner(), projectName, null);
            if (refusal == null) {
                continue;
            }
            final var outcome = disable("Schedule '" + schedule.getName() + "' in " + projectName, refusal,
                    () -> ScheduleOperationHelper.disable(projectName, schedule, refusal));
            if (outcome == Outcome.INTERRUPTED) {
                return false;
            }
            if (outcome == Outcome.DISABLED) {
                restamped.add(AdminRecordKey.schedule(projectName, schedule.getName()));
            }
        }
        return true;
    }

    private interface Lister<T> {
        List<T> list(String projectName) throws IOException;
    }

    private interface Disabler<T> {
        boolean disable(String projectName, T definition, String reason) throws IOException, InterruptedException;
    }

    private record Revocable<T extends StoredDefinition>(String kind, String plural, Lister<T> lister,
            Function<T, String> definerOf, Disabler<T> disabler, BiFunction<String, String, AdminRecordKey> keyOf) {
    }

    private static final Revocable<EntrypointDefinition> ENTRYPOINTS = new Revocable<>("Entrypoint", "entrypoints",
            EntrypointOperationHelper::entrypointsIn, EntrypointDefinition::getDefiner,
            EntrypointOperationHelper::disable, AdminRecordKey::entrypoint);
    private static final Revocable<MiddlewareDefinition> MIDDLEWARES = new Revocable<>("Middleware", "middlewares",
            MiddlewareOperationHelper::middlewaresIn, MiddlewareDefinition::getDefiner,
            MiddlewareOperationHelper::disable, AdminRecordKey::middleware);

    private static <T extends StoredDefinition> boolean revokeAll(Revocable<T> revocable, String projectName,
            Predicate<String> definerInScope, RestampedRecords restamped) {
        final List<T> definitions;
        try {
            definitions = revocable.lister().list(projectName);
        } catch (IOException e) {
            logger.error("Could not read the " + revocable.plural() + " of project '" + projectName
                    + "' to re-check their definers; one whose definer lost its rights is disabled when it next runs",
                    e);
            return true;
        }
        for (final var definition : definitions) {
            final var definer = revocable.definerOf().apply(definition);
            if (outOfScope(definition.isEnabled(), definer, definerInScope)) {
                continue;
            }
            final var refusal = DefinerAuthority.refusal(definer, projectName, null);
            if (refusal == null) {
                continue;
            }
            final var outcome = disable(revocable.kind() + " '" + definition.getName() + "' in " + projectName, refusal,
                    () -> revocable.disabler().disable(projectName, definition, refusal));
            if (outcome == Outcome.INTERRUPTED) {
                return false;
            }
            if (outcome == Outcome.DISABLED) {
                restamped.add(revocable.keyOf().apply(projectName, definition.getName()));
            }
        }
        return true;
    }

    private static boolean outOfScope(boolean enabled, String definer, Predicate<String> definerInScope) {
        return !enabled || definer == null || !definerInScope.test(definer);
    }

    private static String describe(String projectName, String collName, TriggerDefinition trigger) {
        return "Trigger '" + trigger.getName() + "' on " + projectName + "|" + collName;
    }

    private enum Outcome {
        DISABLED, UNCHANGED, INTERRUPTED
    }

    private static Outcome disable(String what, String reason, Disabling disabling) {
        try {
            if (!disabling.run()) {
                return Outcome.UNCHANGED;
            }
            logger.warning(DefinerAuthority.disabledMessage(what, reason));
            return Outcome.DISABLED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.INTERRUPTED;
        } catch (IOException | RuntimeException e) {
            logger.warning("Could not disable " + what + " after its " + reason + ": " + e.getMessage());
            return Outcome.UNCHANGED;
        }
    }
}

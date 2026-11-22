package org.ccg.mpp.ops;

import java.util.UUID;
import org.ccg.mpp.bckg_ops.ScheduleExecutor;
import org.ccg.mpp.bckg_ops.ScheduleRegistry;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.ScheduleDefinition;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.simplejs.host.ProjectHostBindings;
import org.ccg.mpp.simplejs.host.ResourceLimits;

public final class ScheduleDispatcher {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final ScheduleExecutor scheduleExecutor = IocContainer.get(ScheduleExecutor.class);
    private static final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);
    private static final ScriptAdmission admission = IocContainer.get(ScriptAdmission.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(ScheduleDispatcher.class);

    private ScheduleDispatcher() {
    }

    public static void dispatch(ScheduleRegistry.Entry entry) {
        final var definition = entry.getDefinition();
        final var projectName = entry.getProjectName();
        if (registry.get(projectName, entry.getName()) != entry || !definition.isEnabled()) {
            scheduleExecutor.countSkip();
            return;
        }
        final var procedure = cache.getProcedure(projectName, definition.getProcedureName());
        if (procedure == null || !procedure.isEnabled()) {
            scheduleExecutor.countSkip();
            final var reason = "procedure '" + definition.getProcedureName() + "' is missing or disabled; nothing ran";
            registry.warnOnce(entry.key(), reason);
            recordSkip(entry, definition, reason);
            return;
        }
        final var definer = definition.getDefiner();
        final var refusal = DefinerAuthority.refusal(definer, projectName, null);
        if (refusal != null) {
            scheduleExecutor.countSkip();
            final var reason = refusal + "; the schedule is disabled";
            registry.warnOnce(entry.key(), reason);
            recordSkip(entry, definition, reason);
            DefinerAuthority.disableSchedule(projectName, definition, refusal);
            return;
        }
        switch (admission.acquireSlices(definer, projectName)) {
            case ScriptAdmission.Refusal limited -> {
                scheduleExecutor.countSkip();
                recordSkip(entry, definition, "the " + limited.scope()
                        + " script concurrency limit was reached; this occurrence was skipped");
            }
            case ScriptAdmission.Permit permit -> {
                try (permit) {
                    run(entry, definition, procedure.getVersion(), procedure.getSource(), definer);
                }
            }
        }
    }

    private static void run(ScheduleRegistry.Entry entry, ScheduleDefinition definition, long version, String source,
            String definer) {
        final var projectName = entry.getProjectName();
        final var compiled = compiledProcedures.get(projectName, definition.getProcedureName(), version, source);
        final var logPrefix = "SCHEDULE name=" + definition.getName() + " project=" + projectName + " procedure="
                + definition.getProcedureName() + " definer=" + definer;
        final var start = System.currentTimeMillis();
        final var outcome = ScriptOperationHelper.runCompiled(compiled, definition.getArgs(), projectName, definer,
                null, logPrefix, ScriptRunKind.SCHEDULE, definition.getName(), limits(definition), logger::info);
        final var result = outcome.result();
        ScriptRunHistory.record(new ScriptRunRecord(outcome.runId(), ScriptRunKind.SCHEDULE, projectName,
                definition.getName(), definition.getProcedureName(), null, null, definer, definer, start,
                System.currentTimeMillis() - start, 1,
                result.isError() ? ScriptRunRecord.OUTCOME_ERROR : ScriptRunRecord.OUTCOME_OK, result.getErrorName(),
                result.getErrorMessage(), result.getErrorStack(), result.getMetrics(), result.getLogs(),
                result.isLogsTruncated()));
        if (result.isError()) {
            scheduleExecutor.countFailure();
            logger.warning(logPrefix + " outcome=" + result.getErrorName() + ": " + result.getErrorMessage()
                    + ScriptOperationHelper.renderStack(result.getErrorStack())
                    + (result.getLogs().isEmpty() ? "" : " logs=" + result.getLogs()));
        }
    }

    private static void recordSkip(ScheduleRegistry.Entry entry, ScheduleDefinition definition, String reason) {
        ScriptRunHistory.record(new ScriptRunRecord(UUID.randomUUID().toString(), ScriptRunKind.SCHEDULE,
                entry.getProjectName(), definition.getName(), definition.getProcedureName(), null, null,
                definition.getDefiner(), definition.getDefiner(), System.currentTimeMillis(), 0L, 1,
                ScriptRunRecord.OUTCOME_SKIPPED, null, reason, null, null, null, false));
    }

    private static ResourceLimits limits(ScheduleDefinition definition) {
        final var base = ProjectHostBindings.limitsFromConfiguration();
        final var requested = definition.getTimeoutMs() > 0
                ? definition.getTimeoutMs()
                : configuration.getScheduleTimeoutMs();
        final var timeout = Math.min(requested, configuration.longValue(ConfigKey.SCHEDULE_MAX_TIMEOUT_MS));
        return base.withWallClockMillis(timeout).withMaxResultBytes(-1);
    }
}

package org.ccg.mpp.ops;

import java.util.List;
import org.ccg.mpp.simplejs.host.ScriptRunMetrics;

public record ScriptRunRecord(String runId, ScriptRunKind kind, String project, String name, String procedure,
        String collection, String event, String username, String actingUser, long startedAt, long durationMs,
        int attempt, String outcome, String errorName, String errorMessage, List<String> stack,
        ScriptRunMetrics metrics, List<String> logs, boolean logsTruncated) {
    public static final String OUTCOME_OK = "ok";
    public static final String OUTCOME_ERROR = "error";
    public static final String OUTCOME_SKIPPED = "skipped";
    public static final String OUTCOME_DEAD_LETTER = "dead_letter";

    public ScriptRunRecord {
        stack = stack == null ? List.of() : List.copyOf(stack);
        logs = logs == null ? List.of() : List.copyOf(logs);
        metrics = metrics == null ? ScriptRunMetrics.EMPTY : metrics;
    }
}

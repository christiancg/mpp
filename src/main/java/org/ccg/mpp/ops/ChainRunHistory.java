package org.ccg.mpp.ops;

final class ChainRunHistory {
    private ChainRunHistory() {
    }

    static void record(ScriptRunKind kind, String projectName, String name, String procedureName, String definer,
            long startedAt, ScriptOperationHelper.RunOutcome outcome) {
        final var result = outcome.result();
        ScriptRunHistory.record(new ScriptRunRecord(outcome.runId(), kind, projectName, name, procedureName, null, null,
                definer, definer, startedAt, System.currentTimeMillis() - startedAt, 1,
                result.isError() ? ScriptRunRecord.OUTCOME_ERROR : ScriptRunRecord.OUTCOME_OK, result.getErrorName(),
                result.getErrorMessage(), result.getErrorStack(), result.getMetrics(), result.getLogs(),
                result.isLogsTruncated()));
    }
}

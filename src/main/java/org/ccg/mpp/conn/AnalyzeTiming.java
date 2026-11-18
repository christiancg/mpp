package org.ccg.mpp.conn;

import java.util.function.Supplier;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.resp.AggregateAnalyzeResponse;
import org.ccg.mpp.ops.resp.OperationResponse;

final class AnalyzeTiming {
    private AnalyzeTiming() {
    }

    static OperationResponse timed(OperationRequest request, Supplier<OperationResponse> operation) {
        final var analyze = request instanceof AggregateRequest aggReq && aggReq.isAnalyze();
        final var analyzeStart = analyze ? System.currentTimeMillis() : 0L;
        final var responseObj = operation.get();
        if (analyze && responseObj instanceof AggregateAnalyzeResponse analyzeResp
                && analyzeResp.getAnalyzeResult() != null) {
            analyzeResp.getAnalyzeResult().recordTiming(analyzeStart, System.currentTimeMillis());
        }
        return responseObj;
    }
}

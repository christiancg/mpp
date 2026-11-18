package io.github.christiancg.mpp.client.internal;

import io.github.christiancg.mpp.client.AnalyzeResult;
import io.github.christiancg.mpp.client.Document;
import io.github.christiancg.mpp.client.DocumentCodec;
import java.util.List;

public interface QueryTarget<T> {
    DocumentCodec<T> codec();

    List<Document> aggregate(List<PipelineStep> steps, boolean dirtyRead);

    AnalyzeResult analyze(List<PipelineStep> steps, boolean dirtyRead);
}

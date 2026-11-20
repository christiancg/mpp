using System.Collections.Generic;
using System.Collections.Immutable;
using System.Threading;
using System.Threading.Tasks;
using Mpp.Client.Query;

namespace Mpp.Client.Internal;

internal interface IQueryTarget<out T>
{
    string Project { get; }

    string Collection { get; }

    ClientContext Context { get; }

    T DecodeRow(Document row);

    Task<IReadOnlyList<Document>> AggregateAsync(ImmutableArray<PipelineStep> steps, bool dirtyRead, CancellationToken ct);

    Task<AnalyzeResult> AnalyzeAsync(ImmutableArray<PipelineStep> steps, bool dirtyRead, CancellationToken ct);
}

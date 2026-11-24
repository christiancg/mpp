using System.Collections.Generic;

namespace Mpp.Client.Scripting;

public sealed record MiddlewareMatch
{
    public IReadOnlyList<EntrypointMethod>? Methods { get; init; }

    public string? PathPrefix { get; init; }

    public IReadOnlyList<string>? Entrypoints { get; init; }

    public IReadOnlyList<MiddlewareCriteria>? Exclude { get; init; }
}

public sealed record MiddlewareCriteria
{
    public IReadOnlyList<EntrypointMethod>? Methods { get; init; }

    public string? PathPrefix { get; init; }

    public IReadOnlyList<string>? Entrypoints { get; init; }
}

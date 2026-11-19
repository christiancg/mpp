using System.Collections.Generic;

namespace Mpp.Client.Admin;

public sealed record Permissions
{
    public bool Admin { get; init; }

    public IReadOnlyList<GlobalPermission> Global { get; init; } = [];

    public IReadOnlyDictionary<string, DataPermission> Projects { get; init; } = new Dictionary<string, DataPermission>();

    public IReadOnlyDictionary<string, DataPermission> Collections { get; init; } = new Dictionary<string, DataPermission>();

    public IReadOnlyDictionary<string, ScriptPermission> Scripts { get; init; } = new Dictionary<string, ScriptPermission>();

    public static string CollectionKey(string project, string collection) => project + "|" + collection;
}

using System.Collections.Generic;

namespace Mpp.Client.Admin;

public sealed record UserInfo(
    string Username,
    bool IsAdmin,
    IReadOnlyList<string> GlobalPermissions,
    Document ProjectPermissions,
    Document CollectionPermissions,
    Document ScriptPermissions,
    IReadOnlyList<string> OwnedProjects,
    Document Raw);

public sealed record TransactionInfo(
    string DtxId,
    string? Coordinator,
    bool CoordinatorReachable,
    IReadOnlyList<string> Participants,
    long AgeMs,
    Document Raw);

public sealed record ScriptRun(
    string RunId,
    string? Node,
    string Kind,
    string? Project,
    string? Name,
    string? Username,
    long AgeMs,
    Document Raw);

public sealed record TriggerRun(
    string RunId,
    string Status,
    string? Project,
    string? Collection,
    string? Trigger,
    string? Procedure,
    string? Event,
    long Attempts,
    string? LastError,
    Document Raw);

public sealed record ServerInfo(string? State, string? Version, Document Raw);

using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Mpp.Client.Internal;
using Mpp.Client.Mapping;
using Mpp.Client.Protocol;
using Mpp.Client.Scripting;

namespace Mpp.Client;

public sealed class Project
{
    private readonly ClientContext _context;

    internal Project(ClientContext context, string name)
    {
        _context = context;
        Name = Names.Project(name);
    }

    public string Name { get; }

    public async Task CreateAsync(CancellationToken ct = default) =>
        await SendAsync(Operation.CreateProject, RequestEncoder.Request("CREATE_PROJECT", Name), ct).ConfigureAwait(false);

    public async Task DropAsync(CancellationToken ct = default) =>
        await SendAsync(Operation.DropProject, RequestEncoder.Request("DROP_PROJECT", Name), ct).ConfigureAwait(false);

    public async Task<IReadOnlyList<string>> ListCollectionsAsync(CancellationToken ct = default)
    {
        var reply = await SendAsync(Operation.ListCollections, RequestEncoder.Request("LIST_COLLECTIONS", Name), ct)
            .ConfigureAwait(false);
        return ResponseDecoder.Strings(reply["collections"]);
    }

    public Collection<T> Collection<T>(string name, IDocumentCodec<T>? codec = null) =>
        new(new CollectionOps<T>(_context, Name, name, codec ?? new JsonCodec<T>(_context.Json)));

    public Collection<Document> Collection(string name) =>
        new(new CollectionOps<Document>(_context, Name, name, IdentityCodec.Instance));

    public async Task<Document> StatsAsync(CancellationToken ct = default)
    {
        var reply = await SendAsync(Operation.GetProjectStats, RequestEncoder.Request("GET_PROJECT_STATS", Name), ct)
            .ConfigureAwait(false);
        return ResponseDecoder.DocumentOf(reply, "stats") ?? Document.Empty;
    }

    public async Task SetOwnersAsync(IEnumerable<string> owners, CancellationToken ct = default) =>
        await SendAsync(Operation.SetProjectOwners, RequestEncoder.SetProjectOwners(Name, [.. owners]), ct)
            .ConfigureAwait(false);

    public async Task<ScriptResult> RunScriptAsync(string script, Document? args = null, CancellationToken ct = default) =>
        Descriptors.ScriptResult(await SendAsync(Operation.RunScript, ScriptRequestEncoder.RunScript(Name, script, args), ct)
            .ConfigureAwait(false));

    public Procedures Procedures() => new(_context, Name);

    public Schedules Schedules() => new(_context, Name);

    public Entrypoints Entrypoints() => new(_context, Name);

    public async Task<IReadOnlyList<TriggerInfo>> ListTriggersAsync(CancellationToken ct = default)
    {
        var reply = await SendAsync(Operation.ListTriggers, ScriptRequestEncoder.ListTriggers(Name, null), ct)
            .ConfigureAwait(false);
        return [.. ResponseDecoder.Documents(reply, "triggers").Select(Descriptors.Trigger)];
    }

    private Task<System.Text.Json.Nodes.JsonObject> SendAsync(
        Operation operation,
        System.Text.Json.Nodes.JsonObject request,
        CancellationToken ct) =>
        _context.Channel.SendAsync(operation, request, ct);
}

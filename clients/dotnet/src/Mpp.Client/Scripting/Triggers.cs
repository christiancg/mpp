using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Mpp.Client.Internal;
using Mpp.Client.Protocol;

namespace Mpp.Client.Scripting;

public sealed class Triggers
{
    private readonly ClientContext _context;
    private readonly string _project;
    private readonly string _collection;

    internal Triggers(ClientContext context, string project, string collection)
    {
        _context = context;
        _project = project;
        _collection = collection;
    }

    public async Task<TriggerSaved> SaveAsync(TriggerDefinition definition, CancellationToken ct = default)
    {
        var reply = await _context.Channel
            .SendAsync(Operation.SaveTrigger, ScriptRequestEncoder.SaveTrigger(_project, _collection, definition), ct)
            .ConfigureAwait(false);
        return new TriggerSaved(ResponseDecoder.WholeOf(reply["version"]) ?? 0, ResponseDecoder.TextOf(reply["definer"]) ?? string.Empty);
    }

    public async Task DeleteAsync(string name, CancellationToken ct = default) =>
        await _context.Channel
            .SendAsync(Operation.DeleteTrigger, ScriptRequestEncoder.DeleteTrigger(_project, _collection, name), ct)
            .ConfigureAwait(false);

    public async Task<IReadOnlyList<TriggerInfo>> ListAsync(CancellationToken ct = default)
    {
        var reply = await _context.Channel
            .SendAsync(Operation.ListTriggers, ScriptRequestEncoder.ListTriggers(_project, _collection), ct)
            .ConfigureAwait(false);
        return [.. ResponseDecoder.Documents(reply, "triggers").Select(Descriptors.Trigger)];
    }

    public async Task<TriggerTestResult> TestAsync(string name, TriggerEvent triggerEvent, Document sample, CancellationToken ct = default)
    {
        var reply = await _context.Channel
            .SendAsync(Operation.TestTrigger, ScriptRequestEncoder.TestTrigger(_project, _collection, name, triggerEvent, sample), ct)
            .ConfigureAwait(false);
        return new TriggerTestResult(
            ResponseDecoder.TextOf(reply["decision"]) ?? string.Empty,
            ResponseDecoder.DocumentOf(reply, "document"),
            ResponseDecoder.Strings(reply["logs"]),
            ResponseDecoder.BooleanOf(reply["logsTruncated"], false));
    }
}

using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Mpp.Client.Internal;
using Mpp.Client.Protocol;

namespace Mpp.Client.Scripting;

public sealed class Entrypoints
{
    private readonly ClientContext _context;
    private readonly string _project;

    internal Entrypoints(ClientContext context, string project)
    {
        _context = context;
        _project = project;
    }

    public async Task<EntrypointSaved> SaveAsync(EntrypointDefinition definition, CancellationToken ct = default)
    {
        var reply = await _context.Channel
            .SendAsync(Operation.SaveEntrypoint, ScriptRequestEncoder.SaveEntrypoint(_project, definition), ct)
            .ConfigureAwait(false);
        return new EntrypointSaved(ResponseDecoder.WholeOf(reply["version"]) ?? 0, ResponseDecoder.WholeOf(reply["procedureVersion"]) ?? 0);
    }

    public async Task DeleteAsync(string name, CancellationToken ct = default) =>
        await _context.Channel.SendAsync(Operation.DeleteEntrypoint, ScriptRequestEncoder.DeleteEntrypoint(_project, name), ct)
            .ConfigureAwait(false);

    public async Task<IReadOnlyList<EntrypointInfo>> ListAsync(CancellationToken ct = default)
    {
        var reply = await _context.Channel
            .SendAsync(Operation.ListEntrypoints, ScriptRequestEncoder.ListEntrypoints(_project), ct)
            .ConfigureAwait(false);
        return [.. ResponseDecoder.Documents(reply, "entrypoints").Select(Descriptors.Entrypoint)];
    }
}

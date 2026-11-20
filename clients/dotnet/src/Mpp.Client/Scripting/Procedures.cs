using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Mpp.Client.Internal;
using Mpp.Client.Protocol;

namespace Mpp.Client.Scripting;

public sealed class Procedures
{
    private readonly ClientContext _context;
    private readonly string _project;

    internal Procedures(ClientContext context, string project)
    {
        _context = context;
        _project = project;
    }

    public async Task<long> SaveAsync(ProcedureDefinition definition, CancellationToken ct = default)
    {
        var reply = await _context.Channel.SendAsync(Operation.SaveProcedure, ScriptRequestEncoder.SaveProcedure(_project, definition), ct)
            .ConfigureAwait(false);
        return ResponseDecoder.WholeOf(reply["version"]) ?? 0;
    }

    public async Task DeleteAsync(string name, CancellationToken ct = default) =>
        await _context.Channel.SendAsync(Operation.DeleteProcedure, ScriptRequestEncoder.DeleteProcedure(_project, name), ct)
            .ConfigureAwait(false);

    public async Task<IReadOnlyList<ProcedureInfo>> ListAsync(bool? includeSource = null, CancellationToken ct = default)
    {
        var reply = await _context.Channel
            .SendAsync(Operation.ListProcedures, ScriptRequestEncoder.ListProcedures(_project, includeSource), ct)
            .ConfigureAwait(false);
        return [.. ResponseDecoder.Documents(reply, "procedures").Select(Descriptors.Procedure)];
    }

    public async Task<ScriptResult> CallAsync(string name, Document? args = null, CancellationToken ct = default) =>
        Descriptors.ScriptResult(await _context.Channel
            .SendAsync(Operation.CallProcedure, ScriptRequestEncoder.CallProcedure(_project, name, args), ct)
            .ConfigureAwait(false));
}

using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Mpp.Client.Internal;
using Mpp.Client.Protocol;

namespace Mpp.Client.Scripting;

public sealed class Middlewares
{
    private readonly ClientContext _context;
    private readonly string _project;

    internal Middlewares(ClientContext context, string project)
    {
        _context = context;
        _project = project;
    }

    public async Task<MiddlewareSaved> SaveAsync(MiddlewareDefinition definition, CancellationToken ct = default)
    {
        var reply = await _context.Channel
            .SendAsync(Operation.SaveMiddleware, ScriptRequestEncoder.SaveMiddleware(_project, definition), ct)
            .ConfigureAwait(false);
        return new MiddlewareSaved(ResponseDecoder.WholeOf(reply["version"]) ?? 0, ResponseDecoder.WholeOf(reply["procedureVersion"]) ?? 0);
    }

    public async Task DeleteAsync(string name, CancellationToken ct = default) =>
        await _context.Channel.SendAsync(Operation.DeleteMiddleware, ScriptRequestEncoder.DeleteMiddleware(_project, name), ct)
            .ConfigureAwait(false);

    public async Task<IReadOnlyList<MiddlewareInfo>> ListAsync(CancellationToken ct = default)
    {
        var reply = await _context.Channel
            .SendAsync(Operation.ListMiddlewares, ScriptRequestEncoder.ListMiddlewares(_project), ct)
            .ConfigureAwait(false);
        return [.. ResponseDecoder.Documents(reply, "middlewares").Select(Descriptors.Middleware)];
    }
}

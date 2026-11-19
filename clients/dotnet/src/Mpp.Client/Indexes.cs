using System;
using System.Collections.Generic;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Mpp.Client.Internal;
using Mpp.Client.Protocol;

namespace Mpp.Client;

public sealed class Indexes
{
    private readonly ClientContext _context;
    private readonly string _project;
    private readonly string _collection;

    internal Indexes(ClientContext context, string project, string collection)
    {
        _context = context;
        _project = project;
        _collection = collection;
    }

    public async Task CreateAsync(string field, params string[] moreFields)
    {
        ArgumentNullException.ThrowIfNull(moreFields);
        await CreateAsync([field, .. moreFields], CancellationToken.None).ConfigureAwait(false);
    }

    public async Task CreateAsync(IReadOnlyList<string> fields, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(fields);
        if (fields.Count == 0)
        {
            throw new ArgumentException("CreateAsync needs at least one field", nameof(fields));
        }
        await _context.Channel.SendAsync(Operation.CreateIndex, RequestEncoder.CreateIndex(_project, _collection, fields), ct)
            .ConfigureAwait(false);
    }

    public async Task DropAsync(string field, CancellationToken ct = default) =>
        await _context.Channel.SendAsync(Operation.DropIndex, RequestEncoder.DropIndex(_project, _collection, field), ct)
            .ConfigureAwait(false);

    public async Task<ReindexResult> ReindexAsync(params string[] fields) =>
        await ReindexAsync(fields, CancellationToken.None).ConfigureAwait(false);

    public async Task<ReindexResult> ReindexAsync(IReadOnlyList<string> fields, CancellationToken ct = default) =>
        Result(await _context.Channel.SendAsync(Operation.Reindex, RequestEncoder.Reindex(_project, _collection, fields), ct)
            .ConfigureAwait(false));

    public async Task<ReindexResult> ReindexPrimaryKeyAsync(CancellationToken ct = default) =>
        Result(await _context.Channel.SendAsync(Operation.Reindex, RequestEncoder.ReindexPrimaryKey(_project, _collection), ct)
            .ConfigureAwait(false));

    private static ReindexResult Result(JsonObject reply) => new(
        ResponseDecoder.Strings(reply["rebuiltFields"]),
        ResponseDecoder.WholeOf(reply["primaryKeyEntries"]),
        ResponseDecoder.WholeOf(reply["unversionedEntries"]));
}

using System;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Mpp.Client.Errors;
using Mpp.Client.Protocol;

namespace Mpp.Client.Internal;

internal interface IRequestChannel
{
    Task<JsonObject> SendAsync(Operation operation, JsonObject request, CancellationToken ct);
}

internal delegate Task Backoff(TimeSpan delay, CancellationToken ct);

internal static class Backoffs
{
    public static Backoff Jittered { get; } = (delay, ct) =>
        Task.Delay(delay / 2 + delay / 2 * Random.Shared.NextDouble(), ct);

    public static bool IsRetryable(Operation operation, MppException error) => error switch
    {
        ConnectionLostException => operation.IsRead,
        UnavailableException unavailable => unavailable.Code?.IsRetryable == true,
        _ => false,
    };
}

internal sealed class PooledChannel(ConnectionPool pool, RetryPolicy retryPolicy, TimeSpan requestTimeout, Backoff backoff)
    : IRequestChannel
{
    public async Task<JsonObject> SendAsync(Operation operation, JsonObject request, CancellationToken ct)
    {
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                return await OnceAsync(request, ct).ConfigureAwait(false);
            }
            catch (MppException error) when (attempt < retryPolicy.MaxAttempts && Backoffs.IsRetryable(operation, error))
            {
                await backoff(retryPolicy.BackoffBefore(attempt), ct).ConfigureAwait(false);
            }
        }
    }

    private async Task<JsonObject> OnceAsync(JsonObject request, CancellationToken ct)
    {
        var lease = await pool.AcquireAsync(ct).ConfigureAwait(false);
        try
        {
            return ResponseDecoder.Check(await lease.Connection.RequestAsync(request, requestTimeout, ct).ConfigureAwait(false));
        }
        catch (ConnectionLostException)
        {
            lease.Discard();
            throw;
        }
        finally
        {
            lease.Release();
        }
    }
}

internal sealed class PinnedChannel(WireConnection connection, TimeSpan requestTimeout) : IRequestChannel
{
    public async Task<JsonObject> SendAsync(Operation operation, JsonObject request, CancellationToken ct)
    {
        return operation.AllowedInTransaction
            ? ResponseDecoder.Check(await connection.RequestAsync(request, requestTimeout, ct).ConfigureAwait(false))
            : throw new MppException($"{operation} is not allowed inside a transaction", null, null, operation.Name);
    }
}

using System;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using Mpp.Client.Errors;
using Mpp.Client.Internal;
using Mpp.Client.Tests.Support;
using Xunit;

namespace Mpp.Client.Tests.Unit.Internal;

public sealed class ConnectionPoolTests : IAsyncLifetime
{
    private FakeServer _server = null!;

    public ValueTask InitializeAsync()
    {
        _server = FakeServer.Start();
        return ValueTask.CompletedTask;
    }

    public ValueTask DisposeAsync() => _server.DisposeAsync();

    private ConnectionPool Pool(int size = 2, TimeSpan? acquire = null) =>
        new(new ConnectionFactory(
            _server.Settings(settings => settings with { PoolSize = size, AcquireTimeout = acquire ?? TimeSpan.FromSeconds(5) })));

    [Fact]
    public async Task ThePoolGrowsLazilyAndReusesConnections()
    {
        await using var pool = Pool();
        Assert.Empty(_server.Sessions);
        var first = await pool.AcquireAsync(CancellationToken.None);
        var second = await pool.AcquireAsync(CancellationToken.None);
        Assert.Equal(2, pool.ConnectionsInUse);
        first.Release();
        first.Release();
        second.Release();
        Assert.Equal(2, pool.IdleConnections);
        var again = await pool.AcquireAsync(CancellationToken.None);
        again.Release();
        await WireConnectionTests.Eventually(() => _server.Sessions.Count == 2);
        await Task.Delay(50);
        Assert.Equal(2, _server.Sessions.Count);
    }

    [Fact]
    public async Task AWaiterGetsTheReleasedConnection()
    {
        await using var pool = Pool(size: 1);
        var held = await pool.AcquireAsync(CancellationToken.None);
        var waiting = pool.AcquireAsync(CancellationToken.None);
        Assert.False(waiting.IsCompleted);
        held.Release();
        var granted = await waiting;
        Assert.Same(held.Connection, granted.Connection);
        granted.Release();
    }

    [Fact]
    public async Task AcquiringFailsAfterTheAcquireTimeout()
    {
        await using var pool = Pool(size: 1, acquire: TimeSpan.FromMilliseconds(100));
        var held = await pool.AcquireAsync(CancellationToken.None);
        await Assert.ThrowsAsync<PoolExhaustedException>(() => pool.AcquireAsync(CancellationToken.None));
        held.Release();
    }

    [Fact]
    public async Task ADeadConnectionIsNotReused()
    {
        await using var pool = Pool(size: 1);
        var lease = await pool.AcquireAsync(CancellationToken.None);
        lease.Connection.Close();
        lease.Release();
        var next = await pool.AcquireAsync(CancellationToken.None);
        Assert.NotSame(lease.Connection, next.Connection);
        next.Discard();
        next.Release();
        Assert.Equal(0, pool.IdleConnections);
    }

    [Fact]
    public async Task DisposingWaitsAtMostTheShutdownTimeoutForASilentServer()
    {
        _server.Responder = (request, session) =>
        {
            if (request["type"]!.GetValue<string>() != "CLOSE_CONNECTION")
            {
                session.Send(RecordingChannel.Ok(request));
            }
        };
        var pool = Pool();
        var lease = await pool.AcquireAsync(CancellationToken.None);
        lease.Release();
        var held = await pool.AcquireAsync(CancellationToken.None);
        var clock = Stopwatch.StartNew();
        await pool.DisposeAsync();
        Assert.True(clock.Elapsed < TimeSpan.FromSeconds(6), $"took {clock.Elapsed}");
        held.Release();
        await Assert.ThrowsAsync<ObjectDisposedException>(() => pool.AcquireAsync(CancellationToken.None));
        await pool.DisposeAsync();
    }

    [Fact]
    public async Task DisposingWaitsForBorrowedConnections()
    {
        var pool = Pool(size: 1);
        var held = await pool.AcquireAsync(CancellationToken.None);
        var waiter = pool.AcquireAsync(CancellationToken.None);
        var disposing = pool.DisposeAsync().AsTask();
        await Assert.ThrowsAsync<ObjectDisposedException>(() => waiter);
        Assert.False(disposing.IsCompleted);
        held.Release();
        await disposing;
        Assert.False(held.Connection.IsOpen);
    }

    [Fact]
    public async Task AnAuthenticationFailureFailsTheConnect()
    {
        _server.Responder = (request, session) => session.Send(request["type"]!.GetValue<string>() == "AUTHENTICATE"
            ? RecordingChannel.Error(request, "UNAUTHENTICATED", "401-1")
            : RecordingChannel.Ok(request));
        var settings = _server.Settings(settings => settings with { Credentials = new Credentials("alice", "wrong") });
        await Assert.ThrowsAsync<AuthenticationException>(() => MppClient.ConnectAsync(settings));
    }

    [Fact]
    public async Task AnUnreachableHostIsSkippedForTheNextOne()
    {
        var settings = _server.Settings(settings => settings with
        {
            Hosts = [new ServerAddress("127.0.0.1", 1), .. _server.Hosts],
            ConnectTimeout = TimeSpan.FromSeconds(2),
        });
        await using var client = await MppClient.ConnectAsync(settings);
        var unreachable = settings with { Hosts = [new ServerAddress("127.0.0.1", 1)] };
        await Assert.ThrowsAsync<ConnectionLostException>(() => MppClient.ConnectAsync(unreachable));
    }
}

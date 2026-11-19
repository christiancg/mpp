using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;
using Mpp.Client.Errors;
using Mpp.Client.IntegrationTests.Support;
using Xunit;

namespace Mpp.Client.IntegrationTests;

public sealed class ConnectionLimitsIT(ConnectionLimitsIT.Server fixture) : IClassFixture<ConnectionLimitsIT.Server>
{
    [Fact]
    public async Task AnOversizedRequestClosesItsConnectionAndThePoolRecovers()
    {
        var notes = fixture.Client.Project("limits_it").Collection("notes");
        var refused = await Assert.ThrowsAsync<ServerClosedConnectionException>(() => notes.SaveAsync(Document.Of("_id", "big", "text", new string('x', 200 * 1024))));
        Assert.Equal("400-22", refused.RawCode);
        await notes.SaveAsync(Document.Of("_id", "small", "text", "ok"));
        Assert.NotNull(await notes.FindByIdAsync("small"));
    }

    [Fact]
    public async Task AConnectionThatNeverAuthenticatesIsClosedWithItsCode()
    {
        using var raw = new TcpClient();
        await raw.ConnectAsync(IPAddress.Loopback, fixture.Server.Port);
        using var reader = new System.IO.StreamReader(raw.GetStream());
        var line = await reader.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Contains("408-4", line, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ConnectionsPastTheCapAreRefusedAsUnavailable()
    {
        var held = new List<MppClient>();
        try
        {
            MppException? refusal = null;
            for (var index = 0; index < 8 && refusal is null; index++)
            {
                try
                {
                    held.Add(await MppClient.ConnectAsync(fixture.Settings(settings => settings with { PoolSize = 1, RetryPolicy = RetryPolicy.None })));
                }
                catch (MppException error)
                {
                    refusal = error;
                }
            }
            Assert.NotNull(refusal);
            Assert.Equal("503-1", refusal.RawCode);
            Assert.True(refusal is ServerClosedConnectionException or UnavailableException, refusal.GetType().Name);
        }
        finally
        {
            foreach (var client in held)
            {
                await client.DisposeAsync();
            }
        }
        await Eventually.Until(async () =>
        {
            try
            {
                await using var again = await MppClient.ConnectAsync(fixture.Settings());
                return true;
            }
            catch (MppException)
            {
                return false;
            }
        }, ok => ok);
    }

    public sealed class Server : ServerFixture
    {
        protected override IReadOnlyDictionary<string, string> Config => new Dictionary<string, string>
        {
            ["maxRequestSize"] = "128Kb",
            ["maxEntrySize"] = "64Kb",
            ["authTimeoutMs"] = "1500",
            ["maxConnections"] = "4",
        };

        protected override ClientSettings AdjustClient(ClientSettings settings) => settings with { PoolSize = 1 };

        protected override async Task SeedAsync()
        {
            await Client.Project("limits_it").CreateAsync();
            await Client.Project("limits_it").Collection("notes").CreateAsync();
        }
    }
}

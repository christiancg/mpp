using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using Mpp.Client.Errors;
using Mpp.Client.IntegrationTests.Support;
using Xunit;

namespace Mpp.Client.IntegrationTests;

public sealed class TlsIT(TlsIT.Server fixture) : IClassFixture<TlsIT.Server>
{
    private const string StorePassword = "change_it";

    [Fact]
    public async Task AnInsecureClientConnects()
    {
        Assert.NotNull((await fixture.Client.ServerInfoAsync()).Raw);
    }

    [Fact]
    public async Task TrustingTheServersPkcs12CertificateConnects()
    {
        var trusted = TlsSettings.LoadPkcs12(Path.Combine(fixture.Server.WorkDir, "certs", "mpp.p12"), StorePassword);
        await using var client = await MppClient.ConnectAsync(fixture.Settings(settings => settings with
        {
            Tls = new TlsSettings { TrustedCertificates = trusted },
        }));
        Assert.NotNull((await client.ServerInfoAsync()).Raw);
    }

    [Fact]
    public async Task NoTrustFailsTheHandshakeAndPlaintextIsRefused()
    {
        await Assert.ThrowsAsync<ConnectionLostException>(() => MppClient.ConnectAsync(fixture.Settings(settings => settings with { Tls = new TlsSettings() })));
        await Assert.ThrowsAnyAsync<MppException>(() => MppClient.ConnectAsync(fixture.Settings(settings => settings with
        {
            RequestTimeout = TimeSpan.FromSeconds(5),
            RetryPolicy = RetryPolicy.None,
        })));
    }

    public sealed class Server : ServerFixture
    {
        protected override IReadOnlyDictionary<string, string> Config => new Dictionary<string, string>
        {
            ["tlsEnabled"] = "true",
            ["tlsKeystorePath"] = "certs/mpp.p12",
            ["tlsKeystorePassword"] = StorePassword,
        };

        protected override ClientSettings AdjustClient(ClientSettings settings) =>
            settings with { Tls = new TlsSettings { Insecure = true } };
    }
}

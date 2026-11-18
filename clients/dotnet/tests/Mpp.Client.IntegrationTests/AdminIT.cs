using System.Collections.Generic;
using System.Threading.Tasks;
using Mpp.Client.Admin;
using Mpp.Client.Errors;
using Mpp.Client.IntegrationTests.Support;
using Xunit;
using static Mpp.Client.Query.Filters;

namespace Mpp.Client.IntegrationTests;

public sealed class AdminIT(DefaultServer fixture) : IClassFixture<DefaultServer>
{
    private const string BobPassword = "Bob-Secret-1234";

    private Task<MppClient> AsBob(string password = BobPassword) =>
        MppClient.ConnectAsync(fixture.Settings(settings => settings with { Credentials = new Credentials("bob", password) }));

    [Fact]
    public async Task UsersPermissionsOwnersAndPasswords()
    {
        await fixture.Client.Database("admin_it").CreateAsync();
        await fixture.Client.Database("admin_it").Collection("orders").CreateAsync();
        var users = fixture.Client.Admin().Users();
        await users.CreateAsync("bob", BobPassword, new Permissions
        {
            Databases = new Dictionary<string, DataPermission> { ["admin_it"] = DataPermission.Read },
        });
        await using (var bob = await AsBob())
        {
            var orders = bob.Database("admin_it").Collection("orders");
            Assert.Empty(await orders.Query().ToListAsync());
            var refused = await Assert.ThrowsAsync<ForbiddenException>(() => orders.SaveAsync(Document.Of("_id", "x")));
            Assert.StartsWith("403", refused.RawCode, System.StringComparison.Ordinal);
            await Assert.ThrowsAsync<ForbiddenException>(() => bob.Admin().Users().ListAsync());
            await bob.ChangePasswordAsync(BobPassword, "Bob-Secret-5678");
        }
        await Assert.ThrowsAsync<AuthenticationException>(() => AsBob());
        await users.ChangePermissionsAsync("bob", new Permissions
        {
            Databases = new Dictionary<string, DataPermission> { ["admin_it"] = DataPermission.ReadWrite },
        });
        await users.SetPasswordAsync("bob", BobPassword);
        await using (var writer = await AsBob())
        {
            await writer.Database("admin_it").Collection("orders").SaveAsync(Document.Of("_id", "y"));
        }
        await fixture.Client.Database("admin_it").SetOwnersAsync(["bob"]);
        var listed = await users.ListAsync(Field("_id").Eq("bob"));
        var bobInfo = Assert.Single(listed);
        Assert.Contains("admin_it", bobInfo.OwnedDatabases);
        Assert.Contains(await users.ListAsync(), user => user is { Username: "admin", IsAdmin: true });
        await users.DeleteAsync("bob");
        Assert.Empty(await users.ListAsync(Field("_id").Eq("bob")));
    }

    [Fact]
    public async Task DistributedTransactionsAndTriggerRunsAreListed()
    {
        Assert.Empty(await fixture.Client.Admin().Transactions().ListAsync());
        Assert.DoesNotContain(await fixture.Client.Admin().TriggerRuns().ListAsync(TriggerRunStatus.Dead), run => run.Database == "admin_it");
    }
}

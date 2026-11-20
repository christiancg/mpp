using System.Linq;
using System.Threading.Tasks;
using Mpp.Client.Errors;
using Mpp.Client.IntegrationTests.Support;
using Xunit;

namespace Mpp.Client.IntegrationTests;

public sealed class DdlIT(DefaultServer fixture) : IClassFixture<DefaultServer>
{
    [Fact]
    public async Task ProjectsAndCollectionsAreCreatedListedAndDropped()
    {
        var project = fixture.Client.Project("ddl_it");
        await project.CreateAsync();
        Assert.Contains("ddl_it", await fixture.Client.ListProjectsAsync());
        await Assert.ThrowsAsync<ConflictException>(() => project.CreateAsync());
        await project.Collection("orders").CreateAsync();
        await project.Collection("lines").CreateAsync();
        Assert.Equal(["lines", "orders"], [.. (await project.ListCollectionsAsync()).Order()]);
        await project.Collection("lines").DropAsync();
        Assert.Equal(["orders"], await project.ListCollectionsAsync());
        Assert.NotNull((await project.StatsAsync()).Get("collections") ?? true);
        Assert.NotEqual(Document.Empty, await fixture.Client.ProjectStatsAsync());
        var info = await fixture.Client.ServerInfoAsync();
        Assert.NotNull(info.State ?? info.Version);
        await project.DropAsync();
        Assert.DoesNotContain("ddl_it", await fixture.Client.ListProjectsAsync());
    }

    [Fact]
    public async Task OwnersIndexesAndSchemas()
    {
        var project = fixture.Client.Project("ddl_owned");
        await project.CreateAsync();
        await fixture.Client.Admin().Users().CreateAsync("owner_it", "Owner-Secret-1234");
        await project.SetOwnersAsync(["owner_it"]);
        var orders = project.Collection("orders");
        await orders.CreateAsync();
        await orders.Indexes().CreateAsync("status", "total");
        await orders.SaveAsync(Document.Of("_id", "o", "status", "new", "total", 1));
        Assert.Contains("status", (await orders.Indexes().ReindexAsync("status")).RebuiltFields);
        Assert.Equal(1, (await orders.Indexes().ReindexPrimaryKeyAsync()).PrimaryKeyEntries);
        await orders.Indexes().DropAsync("total");
        Assert.Null(await orders.Schema().GetAsync());
        await orders.Schema().SaveAsync(Document.Parse("{\"type\":\"object\",\"required\":[\"status\"]}"));
        Assert.Equal("object", (await orders.Schema().GetAsync())!.GetString("type"));
        var refused = await Assert.ThrowsAsync<MppException>(() => orders.SaveAsync(Document.Of("_id", "x")));
        Assert.Equal("400-7", refused.RawCode);
        await orders.Schema().DeleteAsync();
        await orders.SaveAsync(Document.Of("_id", "x"));
    }
}

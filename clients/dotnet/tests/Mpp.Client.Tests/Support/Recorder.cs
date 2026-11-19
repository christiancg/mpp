using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Mpp.Client.Internal;
using Mpp.Client.Protocol;

namespace Mpp.Client.Tests.Support;

internal sealed class Recorder
{
    public Recorder()
    {
        Context = RecordingChannel.Context(Channel);
        Shop = new Project(Context, "shop");
        Orders = Shop.Collection("orders");
        TypedOrders = Shop.Collection<Order>("orders");
        Customers = Shop.Collection<Customer>("customers");
        Admin = new Admin.Admin(Context);
    }

    public RecordingChannel Channel { get; } = new();

    public ClientContext Context { get; }

    public Project Shop { get; }

    public Collection<Document> Orders { get; }

    public Collection<Order> TypedOrders { get; }

    public Collection<Customer> Customers { get; }

    public Admin.Admin Admin { get; }

    public RequestEncoder Encoder => Context.Encoder;

    public Task Raw(JsonObject request) => Channel.SendAsync(Operation.ServerInfo, request, CancellationToken.None);
}

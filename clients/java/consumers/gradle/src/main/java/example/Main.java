package example;

import static io.github.christiancg.mpp.client.query.Filters.and;
import static io.github.christiancg.mpp.client.query.Filters.field;

import io.github.christiancg.mpp.client.ClientSettings;
import io.github.christiancg.mpp.client.Document;
import io.github.christiancg.mpp.client.MppClient;
import io.github.christiancg.mpp.client.query.Order;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        final var settings = ClientSettings.builder().host("localhost", 8989).credentials("admin", args[0]).build();
        try (var client = MppClient.connect(settings)) {
            final var orders = client.database("shop").collection("orders");
            orders.save(Document.of("_id", "o-1", "status", "active", "total", 120));
            orders.query().filter(and(field("status").eq("active"), field("total").gt(100)))
                    .sort("total", Order.DESC).limit(10).toList().forEach(System.out::println);
        }
    }
}

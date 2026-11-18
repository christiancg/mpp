package io.github.christiancg.mpp.spring.data;

import io.github.christiancg.mpp.client.ClientSettings;
import io.github.christiancg.mpp.client.MppClient;
import io.github.christiancg.mpp.client.RetryPolicy;
import io.github.christiancg.mpp.spring.data.core.MppTemplate;
import io.github.christiancg.mpp.spring.data.mapping.MppMappingContext;
import io.github.christiancg.mpp.spring.data.repository.support.MppRepositoryFactory;
import io.github.christiancg.mpp.testsupport.CannedReplies;
import io.github.christiancg.mpp.testsupport.FakeServer;
import java.io.IOException;
import java.time.Duration;
import tools.jackson.databind.node.ObjectNode;

public final class Stack implements AutoCloseable {
    public final CannedReplies replies = new CannedReplies();
    public final FakeServer server;
    public final MppClient client;
    public final MppTemplate template;

    public Stack() throws IOException {
        this(8);
    }

    public Stack(int poolSize) throws IOException {
        this.server = new FakeServer(replies);
        this.client = MppClient.connect(ClientSettings.builder().host("127.0.0.1", server.port()).poolSize(poolSize)
                .acquireTimeout(Duration.ofMillis(300)).requestTimeout(Duration.ofSeconds(5))
                .retryPolicy(RetryPolicy.none()).build());
        this.template = new MppTemplate(client, new MppMappingContext("shop"));
    }

    public <R> R repository(Class<R> type) {
        return new MppRepositoryFactory(template).getRepository(type);
    }

    public ObjectNode next(String type) throws InterruptedException {
        return server.nextRequestOfType(type);
    }

    public String steps(String type) throws InterruptedException {
        return next(type).get("aggregationSteps").toString();
    }

    public Stack results(String rowsJson) {
        replies.reply("AGGREGATE", "{\"type\":\"AGGREGATE\",\"status\":\"OK\",\"results\":" + rowsJson + "}");
        return this;
    }

    public Stack count(long count) {
        return results("[{\"count\":" + count + "}]");
    }

    @Override
    public void close() throws IOException {
        client.close();
        server.close();
    }
}

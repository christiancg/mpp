package io.github.christiancg.mpp.client.it;

import io.github.christiancg.mpp.client.MppClient;
import io.github.christiancg.mpp.testsupport.ServerProcess;
import java.time.Duration;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ServerIT {
    protected ServerProcess server;
    protected MppClient client;

    protected Map<String, String> config() {
        return Map.of();
    }

    @BeforeAll
    public void startServer() {
        server = ServerProcess.start(config());
        client = MppClient.connect(ItSettings.of(server).build());
    }

    @AfterAll
    public void stopServer() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @SuppressWarnings("BusyWait")
    protected static <T> T eventually(Supplier<T> attempt, Predicate<T> done) throws InterruptedException {
        final var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        T last = attempt.get();
        while (!done.test(last) && System.nanoTime() < deadline) {
            Thread.sleep(100);
            last = attempt.get();
        }
        return last;
    }
}

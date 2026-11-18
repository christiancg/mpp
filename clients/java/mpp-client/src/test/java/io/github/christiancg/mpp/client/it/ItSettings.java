package io.github.christiancg.mpp.client.it;

import io.github.christiancg.mpp.client.ClientSettings;
import io.github.christiancg.mpp.testsupport.ServerProcess;
import java.time.Duration;

final class ItSettings {
    private ItSettings() {
    }

    static ClientSettings.Builder of(ServerProcess server) {
        return ClientSettings.builder().host("localhost", server.port())
                .credentials(ServerProcess.ADMIN, ServerProcess.PASSWORD).requestTimeout(Duration.ofSeconds(60));
    }
}

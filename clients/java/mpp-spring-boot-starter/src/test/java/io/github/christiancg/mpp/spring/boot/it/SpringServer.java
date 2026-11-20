package io.github.christiancg.mpp.spring.boot.it;

import io.github.christiancg.mpp.client.ClientSettings;
import io.github.christiancg.mpp.client.MppClient;
import io.github.christiancg.mpp.testsupport.ServerProcess;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

final class SpringServer {
    static final String PROJECT = "spring_it";
    static final String ORDERS = "orders";

    private SpringServer() {
    }

    static void createOrders(ServerProcess server) {
        try (var client = MppClient.connect(ClientSettings.builder().host("localhost", server.port())
                .credentials(ServerProcess.ADMIN, ServerProcess.PASSWORD).build())) {
            if (!client.listProjects().contains(PROJECT)) {
                client.project(PROJECT).create();
            }
            if (!client.project(PROJECT).listCollections().contains(ORDERS)) {
                client.project(PROJECT).collection(ORDERS).create();
            }
        }
    }

    static ConfigurableApplicationContext start(ServerProcess server, Map<String, String> extra) {
        final var properties = new LinkedHashMap<String, String>();
        properties.put("mpp.hosts", "localhost:" + server.port());
        properties.put("mpp.username", ServerProcess.ADMIN);
        properties.put("mpp.password", ServerProcess.PASSWORD);
        properties.put("mpp.project", PROJECT);
        properties.put("spring.main.banner-mode", "off");
        properties.putAll(extra);
        final var arguments = new ArrayList<String>();
        properties.forEach((key, value) -> arguments.add("--" + key + "=" + value));
        final var application = new SpringApplication(ItApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run(arguments.toArray(String[]::new));
    }
}

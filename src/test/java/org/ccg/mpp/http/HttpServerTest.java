package org.ccg.mpp.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class HttpServerTest {
    private static final long READ_TIMEOUT_MS = 2000;
    private static final long IDLE_TIMEOUT_MS = 300;
    private static final long MAX_BODY = 64;
    private static final HttpHandler ECHO = request -> {
        try {
            final var body = request.body();
            return new HttpResponse(200, Map.of(HttpResponse.CONTENT_TYPE, HttpResponse.PLAIN_TEXT),
                    (request.head().method() + " " + request.head().path() + " "
                            + new String(body, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8),
                    false);
        } catch (IOException e) {
            return HttpResponse.error(408, "slow").closing();
        }
    };
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stopAccepting();
            server.drain(1000);
        }
    }

    private void started(int maxConnections, HttpHandler handler) throws IOException {
        server = new HttpServer();
        server.start(0, "127.0.0.1", null, List.of(),
                new HttpLimits(maxConnections, MAX_BODY, READ_TIMEOUT_MS, IDLE_TIMEOUT_MS), handler);
    }

    private RawHttpClient client() throws IOException {
        return new RawHttpClient(server.boundPort());
    }

    @Test
    public void test_keep_alive_serves_two_requests_on_one_connection() throws IOException {
        started(0, ECHO);
        try (var client = client()) {
            client.send("POST /a HTTP/1.1\r\nContent-Length: 2\r\n\r\nhiGET /b HTTP/1.1\r\n\r\n");
            final var first = client.readResponse();
            final var second = client.readResponse();

            assertEquals(200, first.status());
            assertEquals("POST /a hi", first.body());
            assertEquals("keep-alive", first.connection());
            assertEquals("GET /b ", second.body());
        }
        assertFalse(server.isTls());
    }

    @Test
    public void test_connection_close_closes() throws IOException {
        started(0, ECHO);
        try (var client = client()) {
            client.send("GET /a HTTP/1.1\r\nConnection: close\r\n\r\n");

            assertEquals("close", client.readResponse().connection());
            assertTrue(client.atEndOfStream());
        }
    }

    @Test
    public void test_http_1_0_closes() throws IOException {
        started(0, ECHO);
        try (var client = client()) {
            client.send("GET /a HTTP/1.0\r\n\r\n");

            assertEquals("close", client.readResponse().connection());
            assertTrue(client.atEndOfStream());
        }
    }

    @Test
    public void test_idle_connection_closes_after_timeout() throws IOException {
        started(0, ECHO);
        try (var client = client()) {
            client.send("GET /a HTTP/1.1\r\n\r\n");
            client.readResponse();

            assertTrue(client.atEndOfStream());
        }
    }

    @Test
    public void test_connection_cap_answers_503() throws IOException {
        started(1, ECHO);
        try (var holder = client(); var refused = client()) {
            holder.send("GET /a HTTP/1.1\r\n\r\n");
            holder.readResponse();

            final var response = refused.readResponse();
            assertEquals(503, response.status());
            assertEquals("close", response.connection());
        }
    }

    @Test
    public void test_zero_connection_cap_means_no_cap() throws IOException {
        started(0, ECHO);
        try (var a = client(); var b = client(); var c = client()) {
            for (final var client : List.of(a, b, c)) {
                client.send("GET /a HTTP/1.1\r\n\r\n");
                assertEquals(200, client.readResponse().status());
            }
        }
    }

    @Test
    public void test_framing_error_closes_connection() throws IOException {
        started(0, ECHO);
        try (var client = client()) {
            client.send("not http\r\n\r\n");

            assertEquals(400, client.readResponse().status());
            assertTrue(client.atEndOfStream());
        }
    }

    @Test
    public void test_head_over_cap_is_431() throws IOException {
        started(0, ECHO);
        try (var client = client()) {
            client.send("GET /a HTTP/1.1\r\nX-Big: " + "x".repeat(20_000) + "\r\n\r\n");

            assertEquals(431, client.readResponse().status());
        }
    }

    @Test
    public void test_body_over_cap_is_413_and_chunked_is_411() throws IOException {
        started(0, ECHO);
        try (var client = client()) {
            client.send("POST /a HTTP/1.1\r\nContent-Length: 65\r\n\r\n");

            assertEquals(413, client.readResponse().status());
        }
        try (var client = client()) {
            client.send("POST /a HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n");

            assertEquals(411, client.readResponse().status());
        }
        try (var client = client()) {
            client.send("POST /a HTTP/1.1\r\nContent-Length: x\r\n\r\n");

            assertEquals(400, client.readResponse().status());
        }
    }

    @Test
    public void test_a_slow_head_is_408() throws IOException {
        server = new HttpServer();
        server.start(0, "127.0.0.1", null, List.of(), new HttpLimits(0, MAX_BODY, 300, IDLE_TIMEOUT_MS), ECHO);
        try (var client = client()) {
            client.send("GET /a HTTP/1.1\r\n");

            assertEquals(408, client.readResponse().status());
        }
    }

    @Test
    public void test_body_is_not_read_before_the_handler_asks() throws IOException {
        started(0, _ -> HttpResponse.error(404, "nothing here"));
        try (var client = client()) {
            client.send("POST /a HTTP/1.1\r\nContent-Length: 5\r\n\r\nhello");
            final var response = client.readResponse();

            assertEquals(404, response.status());
            assertEquals("close", response.connection());
        }
    }

    @Test
    public void test_a_handler_failure_is_500_and_closes() throws IOException {
        started(0, _ -> {
            throw new IllegalStateException("boom");
        });
        try (var client = client()) {
            client.send("GET /a HTTP/1.1\r\n\r\n");
            final var response = client.readResponse();

            assertEquals(500, response.status());
            assertEquals("close", response.connection());
        }
    }

    @Test
    public void test_drain_waits_for_in_flight_request() throws Exception {
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        started(0, _ -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return HttpResponse.error(200, "done");
        });
        try (var client = client()) {
            client.send("GET /a HTTP/1.1\r\n\r\n");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            server.stopAccepting();

            assertFalse(server.drain(100));
            release.countDown();
            assertTrue(server.drain(5000));
            final var response = client.readResponse();
            assertEquals(200, response.status());
            assertEquals("close", response.connection());
        }
        assertEquals(-1, server.boundPort());
    }

    @Test
    public void test_bind_failure_names_http_port_and_the_off_switch() throws IOException {
        try (var taken = new ServerSocket(0)) {
            final var busy = new HttpServer();
            final var limits = new HttpLimits(0, MAX_BODY, READ_TIMEOUT_MS, IDLE_TIMEOUT_MS);
            final var failure = assertThrows(IOException.class,
                    () -> busy.start(taken.getLocalPort(), "", null, List.of(), limits, ECHO));

            assertTrue(failure.getMessage().contains("(httpPort)"), failure.getMessage());
            assertTrue(failure.getMessage().contains("httpPort=0"), failure.getMessage());
            assertEquals(-1, busy.boundPort());
        }
    }

    @Test
    public void test_starting_twice_is_refused() throws IOException {
        started(0, ECHO);
        final var limits = new HttpLimits(0, MAX_BODY, READ_TIMEOUT_MS, IDLE_TIMEOUT_MS);

        assertThrows(IllegalStateException.class, () -> server.start(0, "", null, List.of(), limits, ECHO));
    }
}

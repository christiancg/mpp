package org.ccg.mpp.unit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.ccg.mpp.client.ClientException;
import org.ccg.mpp.client.ClientOptions;
import org.ccg.mpp.client.Connection;
import org.ccg.mpp.client.Requests;
import org.ccg.mpp.unit.client.support.FakeLineServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ConnectionTest {
    private static final Duration ACCEPT_WAIT = Duration.ofSeconds(10);
    private FakeLineServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeLineServer();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    public void test_send_receives_one_reply_per_request() throws IOException {
        server.reply("LIST_PROJECTS", FakeLineServer.ok("LIST_PROJECTS", "\"projects\":[\"a\"]"));
        try (var connection = server.connect()) {
            final var first = connection.send(Requests.listProjects());
            final var second = connection.sendLine("{\"type\":\"LIST_PROJECTS\"}");

            assertTrue(Connection.isOk(first));
            assertEquals(first, second);
        }
        assertEquals(List.of("LIST_PROJECTS", "LIST_PROJECTS", "CLOSE_CONNECTION"), server.types());
    }

    @Test
    public void test_a_line_with_newlines_is_sent_as_one_request() throws IOException {
        try (var connection = server.connect()) {
            connection.sendLine("{\"type\":\n\"LIST_PROJECTS\"\r}");
        }
        assertEquals("{\"type\": \"LIST_PROJECTS\" }", server.lines().getFirst());
    }

    @Test
    public void test_non_json_reply_becomes_an_error_object() throws IOException {
        try (var connection = server.connect()) {
            final var reply = connection.sendLine("not json");

            assertFalse(Connection.isOk(reply));
            assertEquals("ERROR", Connection.stringOf(reply, Connection.STATUS));
            assertEquals("The command is not valid", Connection.stringOf(reply, Connection.MESSAGE));
        }
    }

    @Test
    public void test_crlf_reply_is_parsed() throws IOException {
        server.reply("LIST_PROJECTS", FakeLineServer.ok("LIST_PROJECTS") + "\r");
        try (var connection = server.connect()) {
            assertTrue(Connection.isOk(connection.send(Requests.listProjects())));
        }
    }

    @Test
    public void test_retries_503_16_then_succeeds() throws IOException {
        server.reply("SAVE", FakeLineServer.error("SAVE", "ERROR", "503-16"),
                FakeLineServer.error("SAVE", "ERROR", "503-16"), FakeLineServer.ok("SAVE"));
        try (var connection = server.connect()) {
            assertTrue(Connection.isOk(connection.sendLine("{\"type\":\"SAVE\"}")));
        }
        assertEquals(3, server.types().stream().filter("SAVE"::equals).count());
    }

    @Test
    public void test_retries_a_read_refused_503_17_then_succeeds() throws IOException {
        server.reply("AGGREGATE", FakeLineServer.error("AGGREGATE", "ERROR", "503-17"), FakeLineServer.ok("AGGREGATE"));
        try (var connection = server.connect()) {
            assertTrue(Connection.isOk(connection.sendLine("{\"type\":\"AGGREGATE\"}")));
        }
        assertEquals(2, server.types().stream().filter("AGGREGATE"::equals).count());
    }

    @Test
    public void test_gives_up_after_five_attempts() throws IOException {
        server.reply("SAVE", FakeLineServer.error("SAVE", "ERROR", "503-2"));
        try (var connection = server.connect()) {
            final var reply = connection.sendLine("{\"type\":\"SAVE\"}");

            assertEquals("503-2", Connection.errorCodeOf(reply));
        }
        assertEquals(Connection.MAX_ATTEMPTS, server.types().stream().filter("SAVE"::equals).count());
    }

    @Test
    public void test_non_retryable_is_not_retried() throws IOException {
        server.reply("SAVE", FakeLineServer.error("SAVE", "ERROR", "503-8"));
        try (var connection = server.connect()) {
            assertEquals("503-8", Connection.errorCodeOf(connection.sendLine("{\"type\":\"SAVE\"}")));
        }
        assertEquals(1, server.types().stream().filter("SAVE"::equals).count());
    }

    @Test
    public void test_close_sends_close_connection() throws IOException {
        server.connect().close();
        assertEquals(List.of("CLOSE_CONNECTION"), server.types());
    }

    @Test
    public void test_close_after_the_server_hung_up_does_not_throw() throws IOException {
        server.reply("CLOSE_CONNECTION", FakeLineServer.HANG_UP);
        server.connect().close();
        assertEquals(List.of("CLOSE_CONNECTION"), server.types());
    }

    @Test
    public void test_a_hang_up_is_unreachable() throws IOException {
        server.reply("SAVE", FakeLineServer.HANG_UP);
        try (var connection = server.connect()) {
            final var error = assertThrows(ClientException.class, () -> connection.sendLine("{\"type\":\"SAVE\"}"));
            assertEquals(ClientException.UNREACHABLE, error.exitCode());
        }
    }

    @Test
    public void test_send_ok_names_the_refused_request() throws IOException {
        server.reply("LIST_PROJECTS", FakeLineServer.error("LIST_PROJECTS", "FORBIDDEN", "403-1"));
        try (var connection = server.connect()) {
            final var error = assertThrows(ClientException.class, () -> connection.sendOk(Requests.listProjects()));
            assertEquals(ClientException.FAILED, error.exitCode());
            assertTrue(error.getMessage().startsWith("LIST_PROJECTS failed:"));
        }
    }

    @Test
    public void test_open_and_authenticate() {
        final var options = ClientOptions.parse(new String[]{"--shell", "--port", String.valueOf(server.port())},
                Map.of());
        try (var connection = Connection.open(options)) {
            connection.authenticate("admin", "secret");
        }
        final var authenticate = server.requests().getFirst();
        assertEquals("admin", Connection.stringOf(authenticate, "username"));
        assertEquals("secret", Connection.stringOf(authenticate, "password"));
    }

    @Test
    public void test_a_refused_authentication_is_unreachable() {
        server.reply("AUTHENTICATE", FakeLineServer.error("AUTHENTICATE", "ERROR", "401-3"));
        final var options = ClientOptions.parse(new String[]{"--shell", "--port", String.valueOf(server.port())},
                Map.of());
        try (var connection = Connection.open(options)) {
            final var error = assertThrows(ClientException.class, () -> connection.authenticate("admin", "bad"));
            assertEquals(ClientException.UNREACHABLE, error.exitCode());
        }
    }

    @Test
    public void test_open_on_a_closed_port_is_unreachable() throws IOException {
        final int closedPort;
        try (var probe = loopbackServerSocket()) {
            closedPort = probe.getLocalPort();
        }
        final var options = ClientOptions.parse(new String[]{"--shell", "--port", String.valueOf(closedPort)},
                Map.of());
        final var error = assertThrows(ClientException.class, () -> Connection.open(options).close());
        assertEquals(ClientException.UNREACHABLE, error.exitCode());
    }

    @Test
    public void test_a_failed_tls_handshake_is_unreachable() throws Exception {
        assertHandshakeFails("--tls", "--insecure");
        assertHandshakeFails("--tls");
    }

    private static void assertHandshakeFails(String... tlsFlags) throws Exception {
        try (var closing = loopbackServerSocket()) {
            final var acceptor = Thread.ofVirtual().start(() -> {
                try {
                    closing.accept().close();
                } catch (IOException ignored) {
                }
            });
            final var args = new ArrayList<>(List.of("--shell", "--port", String.valueOf(closing.getLocalPort())));
            args.addAll(List.of(tlsFlags));
            final var options = ClientOptions.parse(args.toArray(String[]::new), Map.of());
            final var error = assertThrows(ClientException.class, () -> Connection.open(options).close());
            assertEquals(ClientException.UNREACHABLE, error.exitCode());
            assertTrue(acceptor.join(ACCEPT_WAIT), "the client never reached this test's own server socket");
        }
    }

    private static ServerSocket loopbackServerSocket() throws IOException {
        return new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }

    @Test
    public void aReplyLongerThanTheCapFails() throws IOException {
        server.reply("LIST_PROJECTS", FakeLineServer.ok("LIST_PROJECTS", "\"blob\":\"" + "x".repeat(4096) + "\""));
        try (var connection = new Connection(new Socket(InetAddress.getLoopbackAddress(), server.port()), Duration.ZERO,
                Duration.ofSeconds(10), 1024)) {
            final var failure = assertThrows(ClientException.class, () -> connection.send(Requests.listProjects()));

            assertEquals(ClientException.FAILED, failure.exitCode());
            assertTrue(failure.getMessage().contains("longer than 1024 bytes"), failure.getMessage());
        }
    }

    @Test
    public void aReplyAtTheCapIsRead() throws IOException {
        final var reply = FakeLineServer.ok("LIST_PROJECTS");
        server.reply("LIST_PROJECTS", reply);
        try (var connection = new Connection(new Socket(InetAddress.getLoopbackAddress(), server.port()), Duration.ZERO,
                Duration.ofSeconds(10), reply.length())) {
            assertTrue(Connection.isOk(connection.send(Requests.listProjects())));
        }
    }

    @Test
    public void aSilentServerTimesOut() throws IOException {
        try (var silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var socket = new Socket(InetAddress.getLoopbackAddress(), silent.getLocalPort());
                var accepted = silent.accept()) {
            final var connection = new Connection(socket, Duration.ZERO, Duration.ofMillis(300), 1024);
            final var failure = assertThrows(ClientException.class, () -> connection.send(Requests.listProjects()));

            assertEquals(ClientException.UNREACHABLE, failure.exitCode());
            assertTrue(failure.getMessage().contains("no reply from the server within 0.3 s"), failure.getMessage());
            assertTrue(accepted.isConnected());
        }
    }

    @Test
    public void theDefaultConnectionHasAReadTimeout() throws IOException {
        try (var connection = server.connect()) {
            assertTrue(Connection.isOk(connection.send(Requests.listProjects())));
        }
        assertTrue(Connection.READ_TIMEOUT.toMillis() > 0);
    }

    @Test
    public void aReplicationTimeoutIsReportedNotResent() throws IOException {
        server.reply("SAVE", FakeLineServer.error("SAVE", "ERROR", "503-3"), FakeLineServer.ok("SAVE"));
        try (var connection = server.connect()) {
            assertEquals("503-3", Connection.errorCodeOf(connection.sendLine("{\"type\":\"SAVE\"}")));
        }
        assertEquals(1, server.types().stream().filter("SAVE"::equals).count());
    }

    @Test
    public void theBudgetRefusalsAreRetried() throws IOException {
        server.reply("AGGREGATE", FakeLineServer.error("AGGREGATE", "ERROR", "503-20"), FakeLineServer.ok("AGGREGATE"));
        server.reply("SAVE", FakeLineServer.error("SAVE", "ERROR", "503-21"), FakeLineServer.ok("SAVE"));
        try (var connection = server.connect()) {
            assertTrue(Connection.isOk(connection.sendLine("{\"type\":\"AGGREGATE\"}")));
            assertTrue(Connection.isOk(connection.sendLine("{\"type\":\"SAVE\"}")));
        }
        assertEquals(2, server.types().stream().filter("AGGREGATE"::equals).count());
        assertEquals(2, server.types().stream().filter("SAVE"::equals).count());
    }

    @Test
    public void aDeeplyNestedReplyIsAnErrorReplyNotACrash() {
        final var reply = Connection.parseReply("[".repeat(1_000_000));

        assertFalse(Connection.isOk(reply));
        assertEquals("ERROR", Connection.stringOf(reply, Connection.STATUS));
    }

    @Test
    public void anOverlongReplyLeavesTheConnectionUsable() throws IOException {
        server.reply("LIST_PROJECTS", FakeLineServer.ok("LIST_PROJECTS", "\"blob\":\"" + "x".repeat(100_000) + "\""));
        try (var connection = new Connection(new Socket(InetAddress.getLoopbackAddress(), server.port()), Duration.ZERO,
                Duration.ofSeconds(10), 1024)) {
            final var failure = assertThrows(ClientException.class, () -> connection.send(Requests.listProjects()));
            assertTrue(Connection.isReplyTooLong(failure));

            assertTrue(Connection.isOk(connection.sendLine("{\"type\":\"LIST_COLLECTIONS\"}")),
                    "the next exchange reads the next line, not the rest of the overlong one");
        }
    }
}

package org.ccg.mpp.unit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.ccg.mpp.client.ClientException;
import org.ccg.mpp.client.ClientOptions;
import org.ccg.mpp.client.Connection;
import org.ccg.mpp.client.Dump;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.unit.client.support.FakeLineServer;
import org.ccg.mpp.unit.client.support.FakeSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DumpPagingTest {
    private static final int DOCUMENTS = 20;
    private static final int PADDING = 200;
    private static final int REPLY_CAP = 2048;
    private static final int LARGE_PADDING = 4 * REPLY_CAP;
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private FakeLineServer server;
    private FakeSource source;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeLineServer();
        source = new FakeSource().project("shop", "orders").documents("shop", "orders", DOCUMENTS, PADDING);
        source.serveOn(server);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private int dump(int replyCap) throws IOException {
        final var options = ClientOptions.parse(new String[]{"--dump"}, Map.of());
        try (var connection = new Connection(new Socket(InetAddress.getLoopbackAddress(), server.port()), Duration.ZERO,
                Duration.ofSeconds(10), replyCap)) {
            return new Dump(connection, options, stdout, new PrintStream(stderr, true, StandardCharsets.UTF_8)).run();
        }
    }

    private static int limitOf(JsonObject request) {
        return request.get("limit").asJsonNumber().getValue().intValue();
    }

    private List<JsonObject> pageRequests() {
        return server.requests().stream()
                .filter(request -> "READ_SNAPSHOT".equals(Connection.stringOf(request, "type"))).toList();
    }

    private String err() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void aTooLongPageIsHalvedAndRetriedAfterTheSameId() throws IOException {
        assertEquals(ClientException.OK, dump(REPLY_CAP));

        final var pages = pageRequests();
        assertEquals(Dump.PAGE_SIZE, limitOf(pages.getFirst()));
        assertNull(Connection.stringOf(pages.get(1), "afterId"), "the halved page asks for the same position again");
        assertEquals(Dump.PAGE_SIZE / 2, limitOf(pages.get(1)));
        assertTrue(err().contains("shop.orders: " + DOCUMENTS + " documents"), err());
    }

    @Test
    public void aPageRefusedWith503_17AfterItsRetriesIsHalved() throws IOException {
        server.respond("READ_SNAPSHOT",
                request -> limitOf(request) > 5
                        ? FakeLineServer.error("READ_SNAPSHOT", "ERROR", "503-17")
                        : source.page(request));

        assertEquals(ClientException.OK, dump(Connection.MAX_REPLY_BYTES));

        assertTrue(pageRequests().stream().anyMatch(page -> limitOf(page) <= 5), "the page shrank until answered");
        assertTrue(err().contains("shop.orders: " + DOCUMENTS + " documents"), err());
    }

    @Test
    public void aSingleDocumentOverTheReplyCapFailsWithItsPosition() {
        new FakeSource().project("shop", "orders").documents("shop", "orders", 3, LARGE_PADDING).serveOn(server);

        final var failure = assertThrows(ClientException.class, () -> dump(REPLY_CAP));

        assertEquals(ClientException.FAILED, failure.exitCode());
        assertTrue(failure.getMessage().contains("shop.orders after the start"), failure.getMessage());
    }

    @Test
    public void aReplyOfTheWrongShapeIsAClientException() {
        server.reply("LIST_PROJECTS", FakeLineServer.ok("LIST_PROJECTS", "\"projects\":[1,2]"));

        final var failure = assertThrows(ClientException.class, () -> dump(Connection.MAX_REPLY_BYTES));

        assertEquals(ClientException.FAILED, failure.exitCode());
        assertTrue(failure.getMessage().contains("unexpected projects"), failure.getMessage());
    }
}

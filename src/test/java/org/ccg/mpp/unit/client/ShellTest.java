package org.ccg.mpp.unit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.ccg.mpp.client.ClientException;
import org.ccg.mpp.client.Shell;
import org.ccg.mpp.unit.client.support.FakeLineServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ShellTest {
    private FakeLineServer server;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeLineServer();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private int runShell(String input) throws IOException {
        try (var connection = server.connect()) {
            return new Shell(connection, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(captured, true, StandardCharsets.UTF_8)).run();
        }
    }

    private String printed() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void test_sends_line_and_pretty_prints() throws IOException {
        server.reply("LIST_PROJECTS", FakeLineServer.ok("LIST_PROJECTS", "\"projects\":[\"shop\"]"));

        assertEquals(ClientException.OK, runShell("{\"type\":\"LIST_PROJECTS\"}\nexit\n"));

        assertTrue(printed().startsWith(Shell.PROMPT + "{\n  \"type\": \"LIST_PROJECTS\""), printed());
        assertTrue(printed().contains("  \"projects\": [\n    \"shop\"\n  ]"), printed());
    }

    @Test
    public void test_exit_and_quit_end_the_session() throws IOException {
        runShell("exit\n{\"type\":\"LIST_PROJECTS\"}\n");
        runShell("  quit  \n{\"type\":\"LIST_PROJECTS\"}\n");

        assertEquals(List.of("CLOSE_CONNECTION", "CLOSE_CONNECTION"), server.types());
    }

    @Test
    public void test_eof_ends_the_session() throws IOException {
        assertEquals(ClientException.OK, runShell("{\"type\":\"LIST_PROJECTS\"}"));

        assertEquals(List.of("LIST_PROJECTS", "CLOSE_CONNECTION"), server.types());
        assertTrue(printed().endsWith(Shell.PROMPT), printed());
    }

    @Test
    public void test_blank_line_ignored() throws IOException {
        runShell("\n   \n{\"type\":\"LIST_PROJECTS\"}\n");

        assertEquals(List.of("LIST_PROJECTS", "CLOSE_CONNECTION"), server.types());
    }

    @Test
    public void test_an_error_reply_does_not_end_the_session() throws IOException {
        server.reply("FIND_BY_ID", FakeLineServer.error("FIND_BY_ID", "NOT_FOUND", "404-2"));

        assertEquals(ClientException.OK, runShell("{\"type\":\"FIND_BY_ID\"}\n{\"type\":\"LIST_PROJECTS\"}\n"));

        assertEquals(List.of("FIND_BY_ID", "LIST_PROJECTS", "CLOSE_CONNECTION"), server.types());
    }
}

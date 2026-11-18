package io.github.christiancg.mpp.client.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class LineReaderTest {
    private static LineReader.Line next(LineReader reader) throws IOException {
        final var line = reader.next();
        assertNotNull(line);
        return line;
    }

    private static String text(LineReader.Line line) {
        return new String(line.bytes(), 0, line.length(), StandardCharsets.UTF_8);
    }

    @Test
    public void test_lines_split_on_newline_and_drop_a_trailing_carriage_return() throws IOException {
        final var reader = new LineReader(new ByteArrayInputStream("a\r\nbb\n\nccc".getBytes(StandardCharsets.UTF_8)),
                100);
        assertEquals("a", text(next(reader)));
        assertEquals("bb", text(next(reader)));
        assertEquals("", text(next(reader)));
        assertNull(reader.next());
    }

    @Test
    public void test_an_oversized_line_is_drained_and_the_next_line_still_reads() throws IOException {
        final var input = "x".repeat(200_000) + "\n{\"ok\":1}\n";
        final var reader = new LineReader(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), 1000);
        final var first = next(reader);
        assertTrue(first.tooLarge());
        assertEquals(0, first.length());
        assertEquals("{\"ok\":1}", text(next(reader)));
    }

    @Test
    public void test_a_long_line_within_the_limit_grows_its_buffer() throws IOException {
        final var line = "y".repeat(150_000);
        final var reader = new LineReader(new ByteArrayInputStream((line + "\n").getBytes(StandardCharsets.UTF_8)),
                1_000_000);
        assertEquals(line, text(next(reader)));
    }
}

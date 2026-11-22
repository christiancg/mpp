package org.ccg.mpp.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class HttpBodyReaderTest {
    private static final long CAP = 10;

    private static HttpRequestHead head(String headers) throws IOException {
        final var text = "POST /x HTTP/1.1\r\n" + headers + "\r\n";
        return HttpRequestHead.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)), 1024);
    }

    @Test
    public void test_reads_exactly_the_declared_length() throws IOException {
        final var length = HttpBodyReader.declaredLength(head("Content-Length: 005\r\n"), CAP);
        final var in = new ByteArrayInputStream("hello-and-more".getBytes(StandardCharsets.US_ASCII));

        assertEquals(5, length);
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), HttpBodyReader.read(in, length));
        assertEquals('-', in.read());
    }

    @Test
    public void test_no_length_means_an_empty_body() throws IOException {
        assertEquals(0, HttpBodyReader.declaredLength(head(""), CAP));
    }

    @Test
    public void test_a_length_over_the_cap_is_too_large() throws IOException {
        final var over = head("Content-Length: 11\r\n");

        assertEquals(10, HttpBodyReader.declaredLength(head("Content-Length: 10\r\n"), CAP));
        assertThrows(HttpBodyReader.TooLarge.class, () -> HttpBodyReader.declaredLength(over, CAP));
    }

    @Test
    public void test_a_transfer_encoding_requires_a_length() throws IOException {
        final var chunked = head("Transfer-Encoding: chunked\r\n");

        assertThrows(HttpBodyReader.LengthRequired.class, () -> HttpBodyReader.declaredLength(chunked, CAP));
    }

    @Test
    public void test_a_signed_repeated_or_non_numeric_length_is_malformed() throws IOException {
        for (final var bad : new String[]{"+5", "-1", "5, 5", "five", "12345678901234567890"}) {
            final var head = head("Content-Length: " + bad + "\r\n");

            assertThrows(HttpRequestHead.Malformed.class, () -> HttpBodyReader.declaredLength(head, CAP), bad);
        }
    }

    @Test
    public void test_a_short_body_is_an_eof() {
        final var in = new ByteArrayInputStream(new byte[3]);

        assertThrows(EOFException.class, () -> HttpBodyReader.read(in, 5));
    }
}

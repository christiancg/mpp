package org.ccg.mpp.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

public class QueryStringTest {

    @Test
    public void test_repeated_keys_keep_every_value_in_order() {
        final var parsed = QueryString.parse("tag=a&expand=lines&tag=b");

        assertEquals(List.of("a", "b"), parsed.get("tag"));
        assertEquals(List.of("lines"), parsed.get("expand"));
        assertEquals(List.of("tag", "expand"), List.copyOf(parsed.keySet()));
    }

    @Test
    public void test_plus_and_percent_escapes_are_decoded() {
        final var parsed = QueryString.parse("q=a+b%20c&caf%C3%A9=%E2%82%AC");

        assertEquals(List.of("a b c"), parsed.get("q"));
        assertEquals(List.of("€"), parsed.get("café"));
    }

    @Test
    public void test_a_key_without_a_value_is_empty_and_empty_pairs_are_skipped() {
        final var parsed = QueryString.parse("flag&&x=");

        assertEquals(List.of(""), parsed.get("flag"));
        assertEquals(List.of(""), parsed.get("x"));
        assertEquals(2, parsed.size());
    }

    @Test
    public void test_raw_utf8_bytes_decode_like_escaped_ones() {
        final var rawUtf8 = new String("q=café".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);

        assertEquals(List.of("café"), QueryString.parse(rawUtf8).get("q"));
    }

    @Test
    public void test_an_empty_or_missing_query_has_no_parameters() {
        assertTrue(QueryString.parse("").isEmpty());
        assertTrue(QueryString.parse(null).isEmpty());
    }

    @Test
    public void test_invalid_escapes_are_malformed() {
        assertThrows(MalformedEncodingException.class, () -> QueryString.parse("x=%2"));
        assertThrows(MalformedEncodingException.class, () -> QueryString.parse("x=%zz"));
        assertThrows(MalformedEncodingException.class, () -> QueryString.parse("x=%C3%28"));
        assertThrows(MalformedEncodingException.class, () -> QueryString.parse("x=café"));
        assertThrows(MalformedEncodingException.class, () -> QueryString.parse("x=€"));
    }

    @Test
    public void test_a_path_segment_keeps_plus_and_decodes_an_encoded_slash() {
        assertEquals("a+b/c", PercentDecoding.decode("a+b%2Fc", false));
        final var plain = "plain-segment";
        assertSame(plain, PercentDecoding.decode(plain, false));
    }
}

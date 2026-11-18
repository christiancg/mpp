package org.ccg.mpp.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ops.index.IndexValueCodec;
import org.junit.jupiter.api.Test;

public class IndexValueCodecTest {

    @Test
    public void test_integer_min_value_normalizes_the_same_as_the_parser() {
        final var fromIndex = IndexValueCodec.indexValueToElement((double) Integer.MIN_VALUE);
        final var fromDocument = new JsonNumber(Integer.toString(Integer.MIN_VALUE));

        assertEquals(fromDocument.hashCode(), fromIndex.hashCode(),
                "the codec and the parser disagreed on exactly Integer.MIN_VALUE, so two equal values hashed"
                        + " differently and an index-backed GROUP_BY emitted two groups with the same key");
        assertEquals(fromDocument, fromIndex);
    }

    @Test
    public void test_an_ordinary_integral_value_still_normalizes_to_an_int() {
        final var fromIndex = IndexValueCodec.indexValueToElement(7.0);
        final var fromDocument = new JsonNumber("7");

        assertEquals(fromDocument, fromIndex);
    }
}

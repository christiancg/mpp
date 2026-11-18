package org.ccg.mpp.unit.simplejs.elements;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.simplejs.elements.SourcePosition;
import org.junit.jupiter.api.Test;

public class SourcePositionTest {
    @Test
    public void test_getters() {
        final var position = new SourcePosition(10, 3, 2, 4);
        assertEquals(10, position.offset());
        assertEquals(3, position.length());
        assertEquals(2, position.line());
        assertEquals(4, position.column());
    }
}

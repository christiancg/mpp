package org.ccg.mpp.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.ccg.mpp.ejson.elements.JsonCustom;
import org.junit.jupiter.api.Test;

public class JsonCustomPlainTextTest {
    @Test
    public void test_custom_shaped_text_gains_a_backslash() {
        assertEquals("\\#abc(: x)", JsonCustom.asPlainText("#abc(: x)"));
        assertEquals("\\#geo(1,2)", JsonCustom.asPlainText("#geo(1,2)"));
    }

    @Test
    public void test_plain_text_is_unchanged() {
        assertEquals("Error: boom", JsonCustom.asPlainText("Error: boom"));
        assertEquals("#ab(x)", JsonCustom.asPlainText("#ab(x)"));
        assertEquals("", JsonCustom.asPlainText(""));
    }

    @Test
    public void test_null_stays_null() {
        assertNull(JsonCustom.asPlainText(null));
    }
}

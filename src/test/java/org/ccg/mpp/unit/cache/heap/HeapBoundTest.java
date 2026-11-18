package org.ccg.mpp.unit.cache.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.ccg.mpp.cache.heap.DocumentHeapSizer;
import org.ccg.mpp.cache.heap.HeapBound;
import org.ccg.mpp.cache.heap.HeapLayout;
import org.ccg.mpp.ejson.custom_types.JsonDateTime;
import org.ccg.mpp.ejson.custom_types.JsonGeo;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonBoolean;
import org.ccg.mpp.ejson.elements.JsonNull;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.junit.jupiter.api.Test;

public class HeapBoundTest {
    private static final List<HeapLayout> LAYOUTS = List.of(new HeapLayout(8, 12, 4), new HeapLayout(12, 16, 8),
            HeapLayout.current());

    private static JsonObject document(String text, String key) {
        final var nested = new JsonObject();
        nested.add(key + "-inner", new JsonString(text + text));
        nested.add(key + "-count", new JsonNumber(7));
        final var list = new JsonArray();
        list.add(new JsonString(text));
        list.add(new JsonNumber(1.5));
        list.add(nested);
        final var document = new JsonObject();
        document.add(key + "-id", new JsonString(text + "-id"));
        document.add(key, new JsonString(text.repeat(40)));
        document.add(key + "-flag", JsonBoolean.TRUE);
        document.add(key + "-none", JsonNull.INSTANCE);
        document.add(key + "-nested", nested);
        document.add(key + "-list", list);
        document.add(key + "-number", new JsonNumber(42));
        return document;
    }

    @Test
    public void theBoundEqualsTheModelWhenEveryStringIsWide() {
        final var wide = document("ŵĩđē", "ķēŷ");
        for (final var layout : LAYOUTS) {
            assertEquals(DocumentHeapSizer.sizeOf(wide, layout), new HeapBound(layout).atMost(wide));
            assertEquals(DocumentHeapSizer.sizeOf(wide, layout), DocumentHeapSizer.sizeAtMost(wide, layout));
        }
    }

    @Test
    public void theBoundNeverUndercountsAndChargesLatinTextAtMostTwice() {
        final var latin = document("latin", "key");
        latin.add("geo", new JsonGeo("#geo(1.5,2.5)"));
        latin.add("at", new JsonDateTime("#datetime(2024-05-06T07:08:09)"));
        for (final var layout : LAYOUTS) {
            final var exact = DocumentHeapSizer.sizeOf(latin, layout);
            final var bound = new HeapBound(layout).atMost(latin);
            assertEquals(DocumentHeapSizer.sizeAtMost(latin, layout), bound);
            assertTrue(bound > exact, bound + " <= " + exact);
            assertTrue(bound <= 2 * exact, bound + " > 2 x " + exact);
        }
    }

    @Test
    public void scalarsAndNullAreBoundedLikeTheModel() {
        final List<JsonBaseElement> scalars = List.of(new JsonNumber(3), JsonBoolean.FALSE, JsonNull.INSTANCE,
                new JsonString(""), new JsonArray());
        for (final var layout : LAYOUTS) {
            final var bound = new HeapBound(layout);
            assertEquals(0L, bound.atMost(null));
            for (final var scalar : scalars) {
                assertEquals(DocumentHeapSizer.sizeOf(scalar, layout), bound.atMost(scalar), scalar.toString());
            }
        }
    }
}

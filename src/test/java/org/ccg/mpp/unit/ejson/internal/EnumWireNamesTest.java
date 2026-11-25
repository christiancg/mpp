package org.ccg.mpp.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ccg.mpp.data.FileEncoding;
import org.ccg.mpp.data.FileWriteMode;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ejson.internal.EnumWireNames;
import org.ccg.mpp.ops.req.ReadFileRequest;
import org.ccg.mpp.ops.req.WriteFileRequest;
import org.junit.jupiter.api.Test;

public class EnumWireNamesTest {
    private final EJson eJson = new EJson();

    @Test
    public void test_an_annotated_constant_is_written_by_its_wire_name() {
        assertEquals("utf8", EnumWireNames.of(FileEncoding.UTF8));
        assertEquals("APPEND", EnumWireNames.of(FileWriteMode.APPEND));
        assertEquals("\"base64\"", eJson.toJson(FileEncoding.BASE64));
        final var request = new WriteFileRequest("shop");
        request.setEncoding(FileEncoding.UTF8);
        request.setMode(FileWriteMode.CREATE);
        final var json = eJson.toJson(request);
        assertTrue(json.contains("\"encoding\":\"utf8\""), json);
        assertTrue(json.contains("\"mode\":\"CREATE\""), json);
    }

    @Test
    public void test_a_constant_reads_back_by_its_wire_name_or_its_constant_name() {
        assertEquals(FileEncoding.UTF8, EnumWireNames.constantOf(FileEncoding.class, "utf8"));
        assertEquals(FileEncoding.UTF8, EnumWireNames.constantOf(FileEncoding.class, "UTF8"));
        assertNull(EnumWireNames.constantOf(FileEncoding.class, "Utf8"));
        assertEquals(FileEncoding.BASE64, eJson.fromJson(new JsonString("base64"), FileEncoding.class));
        assertNull(eJson.fromJson(new JsonString("hex"), FileEncoding.class));
        assertEquals(FileEncoding.UTF8, eJson
                .fromJson("{\"projectName\":\"shop\",\"encoding\":\"utf8\"}", ReadFileRequest.class).getEncoding());
        assertEquals(FileEncoding.BASE64, eJson
                .fromJson("{\"projectName\":\"shop\",\"encoding\":\"BASE64\"}", ReadFileRequest.class).getEncoding());
    }
}

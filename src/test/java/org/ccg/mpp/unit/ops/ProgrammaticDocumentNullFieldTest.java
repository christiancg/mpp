package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonNull;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.IndexHelper;
import org.ccg.mpp.ops.filter.FieldPredicateFactory;
import org.ccg.mpp.ops.req.agg.FieldOperatorType;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ProgrammaticDocumentNullFieldTest {
    private static final String FIELD = "procedure";
    private final EJson eJson = IocContainer.get(EJson.class);
    private Cache cache;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        cache = IocContainer.get(Cache.class);
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private static JsonObject rowWithNullField(String id) {
        final var document = new JsonObject();
        document.add(Globals.PK_FIELD, new JsonString(id));
        document.addProperty(FIELD, (String) null);
        return document;
    }

    private JsonObject afterRoundTrip(JsonObject document) {
        return eJson.fromJson(eJson.toJson(document), JsonObject.class);
    }

    private static boolean matches(JsonObject document, FieldOperatorType operation, String operand) {
        final var value = operand == null ? JsonNull.INSTANCE : new JsonString(operand);
        return FieldPredicateFactory.getTester(new FieldOperator(operation, FIELD, value), operation).test(document,
                FIELD);
    }

    @Test
    public void test_a_cached_document_and_its_disk_form_answer_equals_null_alike() {
        final var inMemory = rowWithNullField("r1");
        final var fromDisk = afterRoundTrip(inMemory);

        assertAll(
                () -> assertEquals(matches(fromDisk, FieldOperatorType.EQUALS, null),
                        matches(inMemory, FieldOperatorType.EQUALS, null)),
                () -> assertEquals(matches(fromDisk, FieldOperatorType.NOT_EQUALS, null),
                        matches(inMemory, FieldOperatorType.NOT_EQUALS, null)),
                () -> assertSame(JsonNull.INSTANCE, inMemory.get(FIELD)),
                () -> assertSame(JsonNull.INSTANCE, fromDisk.get(FIELD)));
    }

    @Test
    public void test_a_cached_document_and_its_disk_form_answer_contains_alike() {
        final var inMemory = rowWithNullField("r2");
        final var fromDisk = afterRoundTrip(inMemory);

        assertEquals(matches(fromDisk, FieldOperatorType.CONTAINS, "x"),
                matches(inMemory, FieldOperatorType.CONTAINS, "x"));
    }

    @Test
    public void test_create_index_over_a_cached_null_valued_field_does_not_throw() throws IOException {
        final var entry = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, rowWithNullField("r3"));
        entry.set_id("r3");
        TestUtils.cacheEntry(cache, TestGlobals.PROJECT, TestGlobals.COLL, entry);

        assertDoesNotThrow(() -> IndexHelper.createIndex(TestGlobals.PROJECT, TestGlobals.COLL, FIELD));
    }
}

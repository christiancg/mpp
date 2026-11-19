package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminPageEntry;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.RequestParser;
import org.ccg.mpp.ops.resp.AggregateResponse;
import org.ccg.mpp.ops.resp.DeleteManyResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ConditionalDeleteTest {
    private static final int DOCUMENTS = 40;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final Configuration configuration = Configuration.getInstance();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "deleteMaxMatches", ConfigKey.DELETE_MAX_MATCHES.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void seed(String filler) {
        final var request = new BulkSaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        final var documents = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            final var document = new JsonObject();
            document.add(Globals.PK_FIELD, new JsonString("d" + i));
            document.add("group", new JsonString(i % 4 == 0 ? "a" : "b"));
            document.add("n", new JsonNumber(i));
            document.add("filler", new JsonString(filler));
            documents.add(document);
        }
        request.setObjects(documents);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private OperationResponse send(String json) {
        return processor.processMessage(RequestParser.parseRequest(json));
    }

    private OperationResponse deleteWhere(String condition) {
        return send("{\"type\":\"DELETE\",\"projectName\":\"" + TestGlobals.PROJECT + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"condition\":" + condition + "}");
    }

    private Set<String> ids(String steps) {
        final var response = send("{\"type\":\"AGGREGATE\",\"projectName\":\"" + TestGlobals.PROJECT
                + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"aggregationSteps\":" + steps + "}");
        final var ids = new TreeSet<String>();
        if (response instanceof AggregateResponse aggregate) {
            aggregate.getResults().forEach(row -> ids.add(row.get(Globals.PK_FIELD).asJsonString().getValue()));
        }
        return ids;
    }

    private Set<String> remaining() {
        return ids("[]");
    }

    private static String equalsGroup(String group) {
        return "{\"fieldOperatorType\":\"EQUALS\",\"field\":\"group\",\"value\":\"" + group + "\"}";
    }

    @Test
    public void test_deletes_every_match_and_answers_the_count() {
        seed("x");

        final var response = deleteWhere(equalsGroup("a"));

        assertEquals(10L, assertInstanceOf(DeleteManyResponse.class, response).getDeletedCount());
        final var remaining = remaining();
        assertEquals(30, remaining.size());
        for (var i = 0; i < DOCUMENTS; i++) {
            assertEquals(i % 4 != 0, remaining.contains("d" + i), "d" + i);
        }
    }

    @Test
    public void test_no_match_answers_ok_with_zero_and_writes_nothing() throws Exception {
        seed("x");
        final var rowsBefore = cache.getPkIndexAndLoadIfNecessary(TestGlobals.PROJECT, TestGlobals.COLL).size();

        final var response = deleteWhere(equalsGroup("missing"));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(0L, assertInstanceOf(DeleteManyResponse.class, response).getDeletedCount());
        assertEquals(rowsBefore, cache.getPkIndexAndLoadIfNecessary(TestGlobals.PROJECT, TestGlobals.COLL).size());
    }

    @Test
    public void test_a_conjunction_condition_deletes_only_the_documents_matching_every_leg() {
        seed("x");

        final var response = deleteWhere("{\"conjunctionType\":\"AND\",\"operators\":[" + equalsGroup("b")
                + ",{\"fieldOperatorType\":\"SMALLER_THAN\",\"field\":\"n\",\"value\":10}]}");

        assertEquals(7L, assertInstanceOf(DeleteManyResponse.class, response).getDeletedCount());
        assertEquals(33, remaining().size());
    }

    @Test
    public void test_an_indexed_condition_and_a_scanned_one_delete_the_same_ids() {
        seed("x");
        assertEquals(OperationStatus.OK, processor
                .processMessage(new CreateIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "group")).getStatus());

        assertEquals(10L, assertInstanceOf(DeleteManyResponse.class, deleteWhere(equalsGroup("a"))).getDeletedCount());

        final var indexed = ids("[{\"type\":\"FILTER\",\"operator\":" + equalsGroup("b") + "}]");
        final var scanned = ids(
                "[{\"type\":\"SKIP\",\"skip\":0},{\"type\":\"FILTER\",\"operator\":" + equalsGroup("b") + "}]");
        assertEquals(scanned, indexed);
        assertEquals(30, indexed.size());
        assertEquals(Set.of(), ids("[{\"type\":\"FILTER\",\"operator\":" + equalsGroup("a") + "}]"));
    }

    @Test
    public void test_more_than_delete_max_matches_is_400_44_and_deletes_nothing() throws Exception {
        seed("x");
        TestUtils.setPrivateField(configuration, "deleteMaxMatches", "9");

        final var response = deleteWhere(equalsGroup("a"));

        assertEquals(ErrorCode.DELETE_MATCHES_TOO_MANY.getCode(), response.getErrorCode());
        assertEquals(DOCUMENTS, remaining().size());
    }

    @Test
    public void test_exactly_delete_max_matches_is_allowed() throws Exception {
        seed("x");
        TestUtils.setPrivateField(configuration, "deleteMaxMatches", "10");

        assertEquals(10L, assertInstanceOf(DeleteManyResponse.class, deleteWhere(equalsGroup("a"))).getDeletedCount());
    }

    @Test
    public void test_matches_on_several_pages_vacuum_the_emptied_ones() throws Exception {
        seed("y".repeat(200_000));
        final var pages = cache.getAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL).size();

        final var response = deleteWhere("{\"fieldOperatorType\":\"SMALLER_THAN\",\"field\":\"n\",\"value\":20}");

        assertEquals(20L, assertInstanceOf(DeleteManyResponse.class, response).getDeletedCount());
        assertEquals(20, remaining().size());
        final var live = cache.getAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL).stream()
                .mapToLong(AdminPageEntry::getPageSize).sum();
        assertEquals(20L * survivorBytes(), live, "the occupancy rows count only the survivors");
        assertEquals(pages, cache.getAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL).size(),
                "an emptied page keeps its row at size 0");
    }

    private long survivorBytes() throws Exception {
        return cache.getEntriesByIds(TestGlobals.PROJECT, TestGlobals.COLL, Set.of("d20")).getFirst().byteSize();
    }

    @Test
    public void test_a_script_condition_is_evaluated_per_document() {
        seed("x");

        final var response = deleteWhere("{\"script\":\"export default (d) => d.n >= 30;\"}");

        assertEquals(10L, assertInstanceOf(DeleteManyResponse.class, response).getDeletedCount());
        assertEquals(30, remaining().size());
    }

    @Test
    public void test_a_condition_on_an_unregistered_collection_answers_404_11() {
        final var response = send("{\"type\":\"DELETE\",\"projectName\":\"" + TestGlobals.PROJECT
                + "\",\"collectionName\":\"neverCreated\",\"condition\":" + equalsGroup("a") + "}");

        assertEquals("404-11", response.getErrorCode());
    }
}

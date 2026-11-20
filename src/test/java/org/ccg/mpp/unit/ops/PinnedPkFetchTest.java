package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.UserCache;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.IndexHelper;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.PipelineContext;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;
import org.ccg.mpp.ops.req.agg.step.CountAggregationStep;
import org.ccg.mpp.ops.req.agg.step.JoinAggregationStep;
import org.ccg.mpp.ops.req.agg.step.SkipAggregationStep;
import org.ccg.mpp.ops.req.agg.step.SortAggregationStep;
import org.ccg.mpp.ops.resp.AggregateResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PinnedPkFetchTest {
    private static final String PROJECT = TestGlobals.PROJECT;
    private static final String COLL = TestGlobals.COLL;
    private static final String JOINED = TestGlobals.JOIN_COLL;

    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final Configuration configuration = Configuration.getInstance();
    private Long savedMaxMemory;
    private FileSystem spied;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        TestUtils.createTestJoinCollection();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.setPrivateField(userCache, "fs", fs);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void insert(String collName, int count, String field, ValueOf value) throws Exception {
        final var entries = new ArrayList<DbEntry>(count);
        for (var i = 0; i < count; i++) {
            final var data = new JsonObject();
            data.addProperty("_id", collName + "-" + i);
            value.put(data, field, i);
            entries.add(DbEntry.fromJsonObject(PROJECT, collName, data));
        }
        fs.bulkInsertIntoCollection(PROJECT, collName, entries);
    }

    @FunctionalInterface
    private interface ValueOf {
        void put(JsonObject data, String field, int i);
    }

    private void index(String collName, String field) throws Exception {
        IndexHelper.createIndex(PROJECT, collName, field);
        cache.getAdminCollectionEntry(PROJECT, collName).setIndexes(Set.of(field));
    }

    private void spyOnTinyCache() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", 1024L);
        spied = spy(fs);
        TestUtils.setPrivateField(userCache, "fs", spied);
    }

    private List<JsonObject> aggregate(BaseAggregationStep... steps) {
        final var request = new AggregateRequest(PROJECT, COLL);
        request.setAggregationSteps(List.of(steps));
        final var response = IocContainer.get(OperationProcessor.class).processMessage(request);
        return assertInstanceOf(AggregateResponse.class, response, response.getMessage()).getResults();
    }

    @Test
    void anIndexedSortPointScansItsFirstChunksThenReadsThePkIndexOnce() throws Exception {
        insert(COLL, 2000, "price", (data, field, i) -> data.addProperty(field, (i * 37) % 2000));
        index(COLL, "price");
        spyOnTinyCache();

        final var counted = aggregate(new SortAggregationStep("price", true), new SkipAggregationStep(0),
                new CountAggregationStep());

        assertEquals(2000, counted.getFirst().get("count").asJsonNumber().asInteger());
        verify(spied, times(1)).readWholePkIndexFile(PROJECT, COLL);
        verify(spied, times(PipelineContext.POINT_SCANS_BEFORE_PIN)).pkRowsFor(eq(PROJECT), eq(COLL), anyCollection());
    }

    @Test
    void aJoinResolvesEveryValueWithOneReadOfTheRemoteIndex() throws Exception {
        insert(COLL, 1000, "ref", (data, field, i) -> data.addProperty(field, i == 1 ? "V0" : "v" + i));
        insert(JOINED, 1000, "key", (data, field, i) -> data.addProperty(field, "v" + i));
        index(JOINED, "key");
        spyOnTinyCache();

        final var joined = aggregate(new JoinAggregationStep(JOINED, "ref", "key", "match"));

        verify(spied, times(1)).readWholeFieldIndexFiles(eq(PROJECT), eq(JOINED), eq("key"), eq(String.class));
        assertEquals(1000, joined.size());
        final var matched = new HashSet<String>();
        for (final var document : joined) {
            final var matches = document.get("match");
            if (matches != null && matches.isJsonArray() && !matches.asJsonArray().isEmpty()) {
                assertEquals(1, matches.asJsonArray().size(), document.toString());
                matched.add(document.get("_id").asJsonString().getValue());
            }
        }
        assertEquals(999, matched.size(), "the one lookup of the folded v0/V0 pair still answers v0");
        assertTrue(matched.contains(COLL + "-0"));
    }
}

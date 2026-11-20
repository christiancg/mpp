package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.HybridClock;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OnDiskNameRegistry;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.admin.CollectionOperationHelper;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.RequestParser;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CollectionIncarnationTest {
    private static final String FORGED_TARGET = "incarnation_target";
    private static final long FORGED = Long.MAX_VALUE - 1000;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final HybridClock clock = IocContainer.get(HybridClock.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private long incarnationOf(String collName) {
        return cache.getAdminCollectionEntry(TestGlobals.PROJECT, collName).getIncarnation();
    }

    private static CreateCollectionRequest forgedCreate(long incarnation) {
        return (CreateCollectionRequest) RequestParser.parseRequest("{\"type\":\"CREATE_COLLECTION\",\"projectName\":\""
                + TestGlobals.PROJECT + "\",\"collectionName\":\"" + FORGED_TARGET + "\",\"incarnation\":" + incarnation
                + ",\"incarnationText\":\"" + incarnation + "\"}");
    }

    @Test
    public void test_a_local_create_mints_an_incarnation_from_the_clock() {
        final var before = clock.current();

        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, "local_coll")).getStatus());

        assertTrue(incarnationOf("local_coll") > before);
    }

    @Test
    public void test_a_duplicate_create_keeps_the_existing_incarnation() {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, "dupIncColl")).getStatus());
        final var first = incarnationOf("dupIncColl");

        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, "dupIncColl")).getStatus());

        assertEquals(first, incarnationOf("dupIncColl"));
    }

    @Test
    public void test_a_client_supplied_incarnation_is_ignored_and_one_is_minted() {
        final var response = CollectionOperationHelper.processCreateCollectionOperation(forgedCreate(FORGED));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotEquals(FORGED, incarnationOf(FORGED_TARGET));
        assertTrue(incarnationOf(FORGED_TARGET) > 0);
    }

    @Test
    public void test_a_client_supplied_incarnation_does_not_advance_the_clock() {
        CollectionOperationHelper.processCreateCollectionOperation(forgedCreate(FORGED));

        assertTrue(clock.current() < FORGED);
        assertTrue(clock.next() > 0);
    }

    @Test
    public void test_a_duplicate_client_create_keeps_the_existing_incarnation() {
        CollectionOperationHelper.processCreateCollectionOperation(forgedCreate(0));
        final var first = incarnationOf(FORGED_TARGET);

        CollectionOperationHelper.processCreateCollectionOperation(forgedCreate(FORGED));

        assertEquals(first, incarnationOf(FORGED_TARGET));
    }

    @Test
    public void test_case_differing_collection_is_refused() {
        final var created = processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, "caseProbe"));
        assertEquals(OperationStatus.OK, created.getStatus());
        final var colliding = processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, "CaseProbe"));
        assertEquals(ErrorCode.NAME_COLLIDES_ON_DISK.getCode(), colliding.getErrorCode());
    }

    @Test
    public void test_dropping_then_recreating_a_collection_name_is_not_a_collision() {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, "dropProbe")).getStatus());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.PROJECT, "dropProbe")).getStatus());
        final var recreated = processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, "dropProbe"));
        assertEquals(OperationStatus.OK, recreated.getStatus());
        assertNull(OnDiskNameRegistry.collidingCollection(TestGlobals.PROJECT, "dropProbe"));
    }

    @Test
    public void test_a_dropped_collection_name_frees_its_case_variants_too() {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, "freedProbe")).getStatus());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.PROJECT, "freedProbe")).getStatus());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, "FreedProbe")).getStatus());
    }
}

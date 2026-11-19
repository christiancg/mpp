package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MetadataLimitsTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterEach
    void tearDown() throws Exception {
        restoreDefault(ConfigKey.MAX_COLLECTIONS_PER_PROJECT);
        restoreDefault(ConfigKey.MAX_PROJECTS);
        restoreDefault(ConfigKey.MAX_INDEXES_PER_COLLECTION);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void setLimit(ConfigKey key, int value) throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), key.key(), Integer.toString(value));
    }

    private static void restoreDefault(ConfigKey key) throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), key.key(), key.defaultValue());
    }

    @Test
    public void test_collection_count_is_capped_and_re_creation_still_allowed() throws Exception {
        setLimit(ConfigKey.MAX_COLLECTIONS_PER_PROJECT, 2);

        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, "capcoll")).getStatus());
        final var refused = processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, "capcoll2"));
        assertEquals(ErrorCode.TOO_MANY_COLLECTIONS_FOR_PROJECT.getCode(), refused.getErrorCode());

        assertEquals(OperationStatus.OK, processor
                .processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, TestGlobals.COLL)).getStatus());
    }

    @Test
    public void test_project_count_is_capped() throws Exception {
        setLimit(ConfigKey.MAX_PROJECTS, 2);

        assertEquals(OperationStatus.OK, processor.processMessage(new CreateProjectRequest("capdb_two")).getStatus());
        final var refused = processor.processMessage(new CreateProjectRequest("capdb_three"));
        assertEquals(ErrorCode.TOO_MANY_PROJECTS.getCode(), refused.getErrorCode());
    }

    @Test
    public void test_index_count_is_capped_and_re_creation_still_allowed() throws Exception {
        setLimit(ConfigKey.MAX_INDEXES_PER_COLLECTION, 1);

        assertEquals(OperationStatus.OK, processor
                .processMessage(new CreateIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "first")).getStatus());
        final var refused = processor
                .processMessage(new CreateIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "second"));
        assertEquals(ErrorCode.TOO_MANY_INDEXES_FOR_COLLECTION.getCode(), refused.getErrorCode());

        assertEquals(OperationStatus.OK, processor
                .processMessage(new CreateIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "first")).getStatus());
    }
}

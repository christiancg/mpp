package org.ccg.mpp.unit.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.req.RequestParser;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class LockRegistryPrecheckTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static Map<?, ?> locks() throws Exception {
        return TestUtils.getPrivateStaticField(ResourceLocking.class, "locks", Map.class);
    }

    private String answer(String json) {
        return processor.processMessage(RequestParser.parseRequest(json)).getErrorCode();
    }

    private static boolean hasLockFor(Map<?, ?> registry, String collection) {
        return registry.keySet().stream().anyMatch(key -> key.toString().endsWith("|" + collection));
    }

    @Test
    public void test_reads_and_writes_of_unregistered_collections_create_no_lock_entry() throws Exception {
        final var base = "{\"projectName\":\"" + TestGlobals.PROJECT + "\",\"collectionName\":\"";
        assertEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(),
                answer("{\"type\":\"FIND_BY_ID\"," + base.substring(1) + "ghostRead1\",\"_id\":\"a\"}"));
        assertEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(),
                answer("{\"type\":\"AGGREGATE\"," + base.substring(1) + "ghostRead2\",\"aggregationSteps\":[]}"));
        assertEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(),
                answer("{\"type\":\"SAVE\"," + base.substring(1) + "ghostWrite1\",\"object\":{\"_id\":\"a\"}}"));
        assertEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(),
                answer("{\"type\":\"DELETE\"," + base.substring(1) + "ghostWrite2\",\"_id\":\"a\"}"));
        final var registry = locks();
        assertFalse(hasLockFor(registry, "ghostRead1"));
        assertFalse(hasLockFor(registry, "ghostRead2"));
        assertFalse(hasLockFor(registry, "ghostWrite1"));
        assertFalse(hasLockFor(registry, "ghostWrite2"));
    }
}

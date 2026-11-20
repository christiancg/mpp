package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.ccg.mpp.cache.UserCache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.DropProjectRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.resp.AggregateResponse;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AdminProjectReadFreshnessTest {
    private static final String FIRST = "freshness_first";
    private static final String SECOND = "freshness_second";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final UserCache userCache = IocContainer.get(UserCache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        assertEquals(OperationStatus.OK, processor.processMessage(new CreateProjectRequest(FIRST)).getStatus());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationStatus findProjectRecord(String projectName) {
        final var request = new FindByIdRequest(Globals.ADMIN_PROJECT_NAME, Globals.ADMIN_PROJECTS_COLLECTION_NAME);
        request.set_id(projectName);
        return processor.processMessage(request).getStatus();
    }

    private List<String> scannedProjectRecords() {
        final var request = new AggregateRequest(Globals.ADMIN_PROJECT_NAME, Globals.ADMIN_PROJECTS_COLLECTION_NAME);
        request.setAggregationSteps(List.of());
        final var response = processor.processMessage(request);
        assertInstanceOf(AggregateResponse.class, response);
        return ((AggregateResponse) response).getResults().stream()
                .map(record -> record.get(Globals.PK_FIELD).asJsonString().getValue()).sorted().toList();
    }

    @Test
    public void test_a_project_created_after_a_read_is_found() {
        assertEquals(OperationStatus.OK, findProjectRecord(FIRST));
        assertEquals(List.of(FIRST),
                scannedProjectRecords().stream().filter(name -> name.startsWith("freshness")).toList());

        assertEquals(OperationStatus.OK, processor.processMessage(new CreateProjectRequest(SECOND)).getStatus());

        assertEquals(OperationStatus.OK, findProjectRecord(SECOND));
        assertEquals(List.of(FIRST, SECOND),
                scannedProjectRecords().stream().filter(name -> name.startsWith("freshness")).toList());
    }

    @Test
    public void test_a_dropped_project_is_not_served_from_an_earlier_read() {
        assertEquals(OperationStatus.OK, processor.processMessage(new CreateProjectRequest(SECOND)).getStatus());
        assertEquals(OperationStatus.OK, findProjectRecord(FIRST));
        assertEquals(OperationStatus.OK, findProjectRecord(SECOND));

        assertEquals(OperationStatus.OK, processor.processMessage(new DropProjectRequest(FIRST)).getStatus());

        assertNotEquals(OperationStatus.OK, findProjectRecord(FIRST));
        assertEquals(OperationStatus.OK, findProjectRecord(SECOND));
        assertEquals(List.of(SECOND),
                scannedProjectRecords().stream().filter(name -> name.startsWith("freshness")).toList());
    }

    @Test
    public void test_an_admin_read_publishes_nothing_to_the_user_cache() {
        assertEquals(OperationStatus.OK, findProjectRecord(FIRST));
        scannedProjectRecords();

        assertNull(userCache.getCachedCollection(Globals.ADMIN_PROJECT_NAME, Globals.ADMIN_PROJECTS_COLLECTION_NAME));
        assertTrue(userCache.listCacheableResources().stream()
                .noneMatch(resource -> Globals.ADMIN_PROJECT_NAME.equals(resource.projectName())));
    }
}

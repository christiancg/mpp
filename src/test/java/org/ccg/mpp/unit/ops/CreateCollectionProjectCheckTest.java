package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CreateCollectionProjectCheckTest {
    private static final String UNREGISTERED_PROJECT = "unregisteredProject";
    private static final String COLLECTION = "strayColl";
    private final Cache cache = IocContainer.get(Cache.class);
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

    private static File unregisteredProjectFolder() {
        final var folder = new File(TestGlobals.PATH + File.separator + UNREGISTERED_PROJECT);
        assertTrue(folder.mkdirs());
        return folder;
    }

    private static File pageMetadataFolder() {
        return new File(TestGlobals.PATH + File.separator + Globals.ADMIN_PROJECT_NAME + File.separator
                + Globals.ADMIN_PAGES_FOLDER + File.separator
                + String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, UNREGISTERED_PROJECT, COLLECTION));
    }

    private void assertNothingWritten(File projectFolder, CreateCollectionRequest request) {
        final var response = processor.processMessage(request);

        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals(ErrorCode.PROJECT_NOT_FOUND.getCode(), response.getErrorCode());
        assertFalse(new File(projectFolder, COLLECTION).exists());
        assertFalse(pageMetadataFolder().exists());
        assertNull(cache.getAdminCollectionEntry(UNREGISTERED_PROJECT, COLLECTION));
    }

    @Test
    public void test_create_collection_in_an_unregistered_project_folder_writes_nothing() {
        assertNothingWritten(unregisteredProjectFolder(),
                new CreateCollectionRequest(UNREGISTERED_PROJECT, COLLECTION));
    }

    @Test
    public void test_create_collection_in_a_registered_project_still_succeeds() {
        final var response = processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, COLLECTION));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertTrue(new File(TestGlobals.PATH + File.separator + TestGlobals.PROJECT, COLLECTION).isDirectory());
    }
}

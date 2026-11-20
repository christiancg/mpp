package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.io.OwnerOnlyFiles;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.admin.CollectionOperationHelper;
import org.ccg.mpp.ops.admin.LeftoverFolders;
import org.ccg.mpp.ops.admin.ProjectOperationHelper;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class LeftoverFolderAdoptionTest {
    private static final String COLLECTION = "leftover_coll";
    private static final String PROJECT = "leftover_project";
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
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

    private static File[] quarantinedIn(File parent, String prefix) {
        final var found = parent.listFiles((_, name) -> name.startsWith(prefix + Globals.QUARANTINE_INFIX));
        assertNotNull(found);
        return found;
    }

    private static File testProjectFolder() {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.PROJECT);
    }

    private void createCollection(String db) {
        final var response = CollectionOperationHelper
                .processCreateCollectionOperation(new CreateCollectionRequest(db, COLLECTION));
        assertEquals(OperationStatus.OK, response.getStatus());
    }

    private void save(String id) {
        final var save = new SaveRequest(TestGlobals.PROJECT, COLLECTION);
        final var doc = new JsonObject();
        doc.add(Globals.PK_FIELD, new JsonString(id));
        save.setObject(doc);
        save.set_id(id);
        assertEquals(OperationStatus.OK, processor.processMessage(save).getStatus());
    }

    private void leaveAPopulatedUnregisteredCollection() throws Exception {
        createCollection(TestGlobals.PROJECT);
        save("predrop");
        cache.evictCollection(TestGlobals.PROJECT, COLLECTION);
        AdminOperationHelper.deleteCollectionEntry(TestGlobals.PROJECT, COLLECTION);
        AdminOperationHelper.deletePageCollections(TestGlobals.PROJECT, COLLECTION);
    }

    @Test
    public void test_a_schema_loaded_just_before_a_leftover_move_does_not_outlive_it() throws Exception {
        leaveAPopulatedUnregisteredCollection();
        fs.metadataFiles().writeCollectionSchema(TestGlobals.PROJECT, COLLECTION,
                "{\"type\":\"object\",\"required\":[\"legacy\"]}");
        final var original = fs.folderQuarantine();
        final var loadingFirst = Mockito.spy(original);
        Mockito.doAnswer(invocation -> {
            cache.getCollectionSchema(TestGlobals.PROJECT, COLLECTION);
            return invocation.callRealMethod();
        }).when(loadingFirst).moveCollectionAside(TestGlobals.PROJECT, COLLECTION, 0L);
        TestUtils.setPrivateField(fs, "folderQuarantine", loadingFirst);
        try {
            assertTrue(LeftoverFolders.moveAsideUnregisteredCollection(TestGlobals.PROJECT, COLLECTION));
        } finally {
            TestUtils.setPrivateField(fs, "folderQuarantine", original);
        }

        assertNull(cache.getCollectionSchema(TestGlobals.PROJECT, COLLECTION),
                "the leftover folder's schema is gone, so the next incarnation must not be validated against it");
    }

    @Test
    public void test_create_collection_moves_aside_a_populated_unregistered_folder() throws Exception {
        leaveAPopulatedUnregisteredCollection();

        createCollection(TestGlobals.PROJECT);

        assertTrue(cache.getPkIndexAndLoadIfNecessary(TestGlobals.PROJECT, COLLECTION).isEmpty(),
                "a re-created collection must not serve the documents of the incarnation it replaces");
        assertEquals(1, quarantinedIn(testProjectFolder(), COLLECTION).length);
    }

    @Test
    public void test_create_collection_adopts_an_empty_unregistered_folder() throws Exception {
        fs.createCollectionFile(TestGlobals.PROJECT, COLLECTION);

        createCollection(TestGlobals.PROJECT);

        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, COLLECTION));
        assertEquals(0, quarantinedIn(testProjectFolder(), COLLECTION).length);
    }

    @Test
    public void test_create_collection_on_a_registered_name_keeps_its_documents() throws Exception {
        createCollection(TestGlobals.PROJECT);
        save("kept");

        createCollection(TestGlobals.PROJECT);

        assertEquals(1, cache.getPkIndexAndLoadIfNecessary(TestGlobals.PROJECT, COLLECTION).size());
        assertEquals(0, quarantinedIn(testProjectFolder(), COLLECTION).length);
    }

    @Test
    public void test_create_collection_refuses_a_leftover_it_cannot_move() throws Exception {
        assumeTrue(OwnerOnlyFiles.supported(), "only a POSIX file system has permission bits that deny its owner");
        leaveAPopulatedUnregisteredCollection();
        final var dbDir = testProjectFolder();
        assertTrue(dbDir.setWritable(false));
        try {
            final var response = CollectionOperationHelper
                    .processCreateCollectionOperation(new CreateCollectionRequest(TestGlobals.PROJECT, COLLECTION));

            assertEquals(ErrorCode.ERROR_CREATING_COLLECTION.getCode(), response.getErrorCode());
        } finally {
            assertTrue(dbDir.setWritable(true));
        }
    }

    @Test
    public void test_create_project_moves_aside_a_populated_unregistered_folder() throws Exception {
        assertEquals(OperationStatus.OK, ProjectOperationHelper
                .processCreateProjectOperation(new CreateProjectRequest(PROJECT), null).getStatus());
        createCollection(PROJECT);
        fs.metadataFiles().writeProcedure(PROJECT, "oldproc", "{\"name\":\"oldproc\"}");
        cache.evictProject(PROJECT);
        AdminOperationHelper.deleteProjectEntry(PROJECT);

        final var response = ProjectOperationHelper.processCreateProjectOperation(new CreateProjectRequest(PROJECT),
                null);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertTrue(fs.metadataFiles().listProcedureNames(PROJECT).isEmpty(),
                "a dropped project's procedures must not come back");
        assertTrue(cache.getCollectionNamesForProject(PROJECT).isEmpty());
        assertEquals(1, quarantinedIn(new File(TestGlobals.PATH), PROJECT).length);
    }

    @Test
    public void test_create_project_refuses_a_leftover_it_cannot_move() throws Exception {
        assumeTrue(OwnerOnlyFiles.supported(), "only a POSIX file system has permission bits that deny its owner");
        assertTrue(fs.createProjectFolder(PROJECT));
        fs.metadataFiles().writeProcedure(PROJECT, "oldproc", "{\"name\":\"oldproc\"}");
        final var root = new File(TestGlobals.PATH);
        assertTrue(root.setWritable(false));
        try {
            final var response = ProjectOperationHelper.processCreateProjectOperation(new CreateProjectRequest(PROJECT),
                    null);

            assertEquals(ErrorCode.ERROR_CREATING_PROJECT.getCode(), response.getErrorCode());
        } finally {
            assertTrue(root.setWritable(true));
        }
    }
}

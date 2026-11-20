package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.OnDiskNameRegistry;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class OnDiskNameRegistryTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

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

    private void createCollection(String collName) {
        final var request = new CreateCollectionRequest(TestGlobals.PROJECT, collName);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private void registerGroupedPairBehindTheNameCheck() throws Exception {
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.PROJECT, "GroupedPair"));
    }

    @Test
    public void test_exact_name_is_not_a_collision() {
        assertNull(OnDiskNameRegistry.collidingProject(TestGlobals.PROJECT));
        assertNull(OnDiskNameRegistry.collidingCollection(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    private static Stream<Arguments> collidingProjects() {
        return Stream.of(
                Arguments.of("test_case_differing_project_collides upper", TestGlobals.PROJECT.toUpperCase(Locale.ROOT),
                        TestGlobals.PROJECT),
                Arguments.of("test_case_differing_project_collides lower", TestGlobals.PROJECT.toLowerCase(Locale.ROOT),
                        TestGlobals.PROJECT),
                Arguments.of("test_case_differing_admin_project_collides Admin", "Admin", Globals.ADMIN_PROJECT_NAME),
                Arguments.of("test_case_differing_admin_project_collides ADMIN", "ADMIN", Globals.ADMIN_PROJECT_NAME),
                Arguments.of("test_admin_pages_reserved_name_collides", "Admin_Pages",
                        Globals.ADMIN_PAGES_PROJECT_NAME),
                Arguments.of("test_case_differing_cluster_folder_collides", "Cluster", Globals.CLUSTER_FOLDER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collidingProjects")
    public void test_a_project_name_collides_with_its_on_disk_folder(String name, String requested, String expected) {
        assertEquals(expected, OnDiskNameRegistry.collidingProject(requested), name);
    }

    @Test
    public void test_case_differing_collection_collides() {
        assertEquals(TestGlobals.COLL,
                OnDiskNameRegistry.collidingCollection(TestGlobals.PROJECT, TestGlobals.COLL.toUpperCase(Locale.ROOT)));
    }

    @Test
    public void test_case_differing_index_field_collides() {
        final var request = new CreateIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "someField");
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
        assertEquals("someField",
                OnDiskNameRegistry.collidingIndexField(TestGlobals.PROJECT, TestGlobals.COLL, "SOMEFIELD"));
        assertNull(OnDiskNameRegistry.collidingIndexField(TestGlobals.PROJECT, TestGlobals.COLL, "someField"));
    }

    @Test
    public void test_case_differing_definition_name_collides() {
        assertEquals("MyProc", OnDiskNameRegistry.collidingDefinition(List.of("MyProc", "other"), "myproc"));
        assertNull(OnDiskNameRegistry.collidingDefinition(List.of("MyProc", "other"), "MyProc"));
    }

    @Test
    public void test_only_a_project_named_like_the_cluster_folder_is_listed_as_in_it() throws Exception {
        assertEquals(List.of(), OnDiskNameRegistry.registeredProjectsInTheClusterFolder());
        AdminOperationHelper.saveProjectEntry(new AdminProjectEntry("CLUSTER"));
        assertEquals(List.of("CLUSTER"), OnDiskNameRegistry.registeredProjectsInTheClusterFolder());
    }

    @Test
    public void test_unrelated_names_do_not_collide() {
        assertNull(OnDiskNameRegistry.collidingProject("somethingElse"));
        assertNull(OnDiskNameRegistry.collidingCollection(TestGlobals.PROJECT, "unrelatedCollection"));
        assertNull(OnDiskNameRegistry.collidingIndexField(TestGlobals.PROJECT, TestGlobals.COLL, "noSuchField"));
    }

    @Test
    public void test_unknown_project_has_no_colliding_collection() {
        assertNull(OnDiskNameRegistry.collidingCollection("noSuchProject", "anything"));
    }

    @Test
    public void test_empty_registry_answers_null() {
        assertNull(OnDiskNameRegistry.collidingDefinition(List.of(), "anything"));
        assertNull(OnDiskNameRegistry.collidingDefinition(List.of("a"), null));
    }

    @Test
    public void test_on_disk_key_folds_case_only() {
        assertEquals("mycoll", OnDiskNameRegistry.onDiskKey("MyColl"));
        assertEquals("first-name", OnDiskNameRegistry.onDiskKey("First-Name"));
        assertNotEquals(OnDiskNameRegistry.onDiskKey("a_b"), OnDiskNameRegistry.onDiskKey("a-b"));
    }

    @Test
    public void test_grouped_by_on_disk_key_is_empty_when_names_are_distinct() {
        assertTrue(OnDiskNameRegistry.groupedByOnDiskKey().isEmpty());
    }

    @Test
    public void test_grouped_by_on_disk_key_reports_a_case_differing_collection_pair() throws Exception {
        createCollection("groupedPair");
        registerGroupedPairBehindTheNameCheck();
        final var groups = OnDiskNameRegistry.groupedByOnDiskKey();
        assertEquals(1, groups.size());
        assertEquals(List.of("groupedPair", "GroupedPair"), groups.getFirst());
    }

    @Test
    public void test_same_collection_name_in_two_projects_does_not_group() {
        createCollection("perProject");
        final var otherProject = new CreateProjectRequest("otherProject");
        assertEquals(OperationStatus.OK, processor.processMessage(otherProject).getStatus());
        final var request = new CreateCollectionRequest("otherProject", "perProject");
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
        assertTrue(OnDiskNameRegistry.groupedByOnDiskKey().isEmpty());
    }
}

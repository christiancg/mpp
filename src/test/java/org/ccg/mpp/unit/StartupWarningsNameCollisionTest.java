package org.ccg.mpp.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockStatic;

import org.ccg.mpp.StartupWarnings;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.LogWriter;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

public class StartupWarningsNameCollisionTest {
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

    private void createProject(String projectName) {
        assertEquals(OperationStatus.OK, processor.processMessage(new CreateProjectRequest(projectName)).getStatus());
    }

    private static void plantSharedNameCollection() throws Exception {
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.PROJECT, "SharedName"));
    }

    private static void plantProject(String projectName) throws Exception {
        AdminOperationHelper.saveProjectEntry(new AdminProjectEntry(projectName));
    }

    private static void assertLogged(MockedStatic<LogWriter> logWriter, String expected) {
        logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains(expected))));
    }

    @Test
    public void test_two_collections_in_one_project_sharing_a_key_warn() throws Exception {
        createCollection("sharedName");
        plantSharedNameCollection();
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfNamesShareAnOnDiskKey();
            assertLogged(logWriter, "share one on-disk path");
            assertLogged(logWriter, "sharedName, SharedName");
        }
    }

    @Test
    public void test_two_projects_sharing_an_on_disk_key_warn() throws Exception {
        createProject("caseProbe");
        plantProject("CaseProbe");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfNamesShareAnOnDiskKey();
            assertLogged(logWriter, "caseProbe, CaseProbe");
        }
    }

    @Test
    public void test_same_name_in_different_projects_does_not_warn() {
        createProject("otherProject");
        createCollection("sharedName");
        final var request = new CreateCollectionRequest("otherProject", "sharedName");
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfNamesShareAnOnDiskKey();
            logWriter.verifyNoInteractions();
        }
    }

    @Test
    public void test_a_project_named_like_the_cluster_folder_is_warned_about() throws Exception {
        plantProject("cluster");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfProjectSharesTheClusterFolder();
            assertLogged(logWriter, "Project 'cluster' is stored in the folder this node keeps its cluster state");
        }
    }

    @Test
    public void test_an_ordinary_project_is_not_warned_about_the_cluster_folder() {
        createProject("clusters");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfProjectSharesTheClusterFolder();
            logWriter.verifyNoInteractions();
        }
    }

    @Test
    public void test_distinct_names_are_silent() {
        createCollection("alpha");
        createCollection("beta");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfNamesShareAnOnDiskKey();
            logWriter.verifyNoInteractions();
        }
    }
}

package org.ccg.mpp.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.StatsView;
import org.ccg.mpp.ops.auth.AuthorizationChecker;
import org.ccg.mpp.ops.req.DeleteFileRequest;
import org.ccg.mpp.ops.req.ListFilesRequest;
import org.ccg.mpp.ops.req.MakeDirectoryRequest;
import org.ccg.mpp.ops.req.MoveFileRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.ReadFileRequest;
import org.ccg.mpp.ops.req.StatFileRequest;
import org.ccg.mpp.ops.req.WriteFileRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class AuthorizationCheckerFilesTest {
    private static final String OWNER = "filesowner";

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        AdminOperationHelper.updateProjectOwners(TestGlobals.PROJECT, List.of(OWNER));
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static AdminUserEntry user(String name, Map<String, PermissionLevel> projects,
            Map<String, PermissionLevel> collections) {
        return new AdminUserEntry(name, "hash", false, new HashSet<>(), new HashMap<>(projects),
                new HashMap<>(collections));
    }

    private static List<OperationRequest> reads() {
        return List.of(new ReadFileRequest(TestGlobals.PROJECT), new StatFileRequest(TestGlobals.PROJECT),
                new ListFilesRequest(TestGlobals.PROJECT));
    }

    private static List<OperationRequest> writes() {
        return List.of(new WriteFileRequest(TestGlobals.PROJECT), new DeleteFileRequest(TestGlobals.PROJECT),
                new MoveFileRequest(TestGlobals.PROJECT), new MakeDirectoryRequest(TestGlobals.PROJECT));
    }

    private static boolean allowed(OperationRequest request, AdminUserEntry user) {
        return AuthorizationChecker.check(request, user).isAllowed();
    }

    @Test
    public void test_project_read_reads_and_project_read_write_writes() {
        final var reader = user("reader", Map.of(TestGlobals.PROJECT, PermissionLevel.READ), Map.of());
        final var writer = user("writer", Map.of(TestGlobals.PROJECT, PermissionLevel.READ_WRITE), Map.of());
        for (final var read : reads()) {
            assertTrue(allowed(read, reader), read.getType().name());
            assertTrue(allowed(read, writer), read.getType().name());
        }
        for (final var write : writes()) {
            assertFalse(allowed(write, reader), write.getType().name());
            assertTrue(allowed(write, writer), write.getType().name());
        }
    }

    @Test
    public void test_a_collection_grant_or_another_project_reaches_no_file() {
        final var collectionOnly = user("collonly", Map.of(),
                Map.of(TestGlobals.PROJECT + "|" + TestGlobals.COLL, PermissionLevel.READ_WRITE));
        final var elsewhere = user("elsewhere", Map.of("otherproject", PermissionLevel.READ_WRITE), Map.of());
        for (final var request : reads()) {
            assertFalse(allowed(request, collectionOnly));
            assertFalse(allowed(request, elsewhere));
        }
        for (final var request : writes()) {
            assertFalse(allowed(request, collectionOnly));
            assertFalse(allowed(request, elsewhere));
        }
    }

    @Test
    public void test_the_stats_show_files_only_to_who_can_read_them() {
        final var reader = user("statsreader", Map.of(TestGlobals.PROJECT, PermissionLevel.READ), Map.of());
        final var collectionOnly = user("statscoll", Map.of(),
                Map.of(TestGlobals.PROJECT + "|" + TestGlobals.COLL, PermissionLevel.READ_WRITE));
        final var admin = new AdminUserEntry("statsroot", "hash", true, new HashSet<>(), new HashMap<>(),
                new HashMap<>());
        assertTrue(StatsView.of(reader).includesFiles(TestGlobals.PROJECT));
        assertTrue(StatsView.of(user(OWNER, Map.of(), Map.of())).includesFiles(TestGlobals.PROJECT));
        assertTrue(StatsView.of(admin).includesFiles(TestGlobals.PROJECT));
        assertFalse(StatsView.of(collectionOnly).includesFiles(TestGlobals.PROJECT));
        assertTrue(StatsView.of(collectionOnly).includesProject(TestGlobals.PROJECT));
        assertFalse(StatsView.of(null).includesFiles(TestGlobals.PROJECT));
    }

    @Test
    public void test_owners_and_admins_reach_everything() {
        final var owner = user(OWNER, Map.of(), Map.of());
        final var admin = new AdminUserEntry("root", "hash", true, new HashSet<>(), new HashMap<>(), new HashMap<>());
        for (final var request : writes()) {
            assertTrue(allowed(request, owner));
            assertTrue(allowed(request, admin));
        }
    }
}

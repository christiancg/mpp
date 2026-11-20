package org.ccg.mpp.unit.ops.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.admin.ProjectOperationHelper;
import org.ccg.mpp.ops.resp.ListProjectsResponse;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ProjectOperationHelperListTest {

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        AdminOperationHelper.saveProjectEntry(new AdminProjectEntry("granted_project"));
        AdminOperationHelper.saveProjectEntry(new AdminProjectEntry("hidden_project"));
        AdminOperationHelper.saveProjectEntry(
                new AdminProjectEntry("owned_project", new ArrayList<>(), new ArrayList<>(List.of("lister"))));
        AdminOperationHelper.saveUserEntry(new AdminUserEntry("lister", "hash", false, new HashSet<>(),
                new HashMap<>(Map.of("granted_project", PermissionLevel.READ)), new HashMap<>()));
        AdminOperationHelper.saveUserEntry(
                new AdminUserEntry("boss", "hash", true, new HashSet<>(), new HashMap<>(), new HashMap<>()));
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static List<String> listedFor(String username) {
        final var response = ProjectOperationHelper.processListProjectsOperation(username);
        return assertInstanceOf(ListProjectsResponse.class, response).getProjects();
    }

    @Test
    public void list_projects_is_filtered_for_the_acting_user() {
        assertEquals(List.of("granted_project", "owned_project"), listedFor("lister"));
    }

    @Test
    public void admin_lists_every_user_project_sorted() {
        assertEquals(List.of("granted_project", "hidden_project", "owned_project"), listedFor("boss"));
    }

    @Test
    public void an_unresolvable_user_lists_nothing() {
        assertEquals(List.of(), listedFor(null));
        assertEquals(List.of(), listedFor("deleted_user"));
    }
}

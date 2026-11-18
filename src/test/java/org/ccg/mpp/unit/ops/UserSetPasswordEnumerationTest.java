package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.HashSet;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.UserOperationHelper;
import org.ccg.mpp.ops.req.CreateUserRequest;
import org.ccg.mpp.ops.req.SetPasswordRequest;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class UserSetPasswordEnumerationTest {
    private static final String CALLER = "plain_caller";
    private static final String OTHER = "plain_other";
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        createPlainUser(CALLER);
        createPlainUser(OTHER);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private static void createPlainUser(String username) {
        final var request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword("password123");
        request.setAdmin(false);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    private String answerFor(String target) {
        final var clientId = clientTracker.registerForwardedClient(CALLER);
        try {
            final var request = new SetPasswordRequest();
            request.setUsername(target);
            request.setCurrentPassword("password123");
            request.setNewPassword("another-password");
            return UserOperationHelper.processSetPassword(request, clientId).getErrorCode();
        } finally {
            clientTracker.removeById(clientId);
        }
    }

    @Test
    public void test_an_existing_and_a_missing_foreign_target_answer_the_same() {
        assertEquals(ErrorCode.NO_PERMISSIONS.getCode(), answerFor(OTHER));
        assertEquals(ErrorCode.NO_PERMISSIONS.getCode(), answerFor("no_such_user_here"));
    }
}

package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.ccg.mpp.cache.AdminCache;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.UserOperationHelper;
import org.ccg.mpp.ops.req.AuthenticateRequest;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class UserOperationHelperCatchTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private AdminCache realAdminCache;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        realAdminCache = TestUtils.getPrivateField(cache, "adminCache", AdminCache.class);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(cache, "adminCache", realAdminCache);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_authenticate_error_when_cache_throws() throws Exception {
        final var throwing = mock(AdminCache.class);
        when(throwing.getAdminUserEntry("u")).thenThrow(new RuntimeException("boom"));
        TestUtils.setPrivateField(cache, "adminCache", throwing);

        final var request = new AuthenticateRequest();
        request.setUsername("u");
        request.setPassword("p");
        final var response = UserOperationHelper.processAuthenticate(request, UUID.randomUUID());
        assertNotEquals(OperationStatus.OK, response.getStatus());
    }
}

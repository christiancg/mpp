package org.ccg.mpp.unit.cache;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.ccg.mpp.cache.CacheResidents;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestUtils;
import org.ccg.mpp.utils.ReflectionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

abstract class CacheFixtureSupport {
    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    protected static void injectCachedEntry(String collId, DbEntry entry) {
        CacheResidents.residentDocument(IocContainer.get(org.ccg.mpp.cache.UserCache.class), collId, entry);
    }

    protected static void injectPkIndex(String collId, List<org.ccg.mpp.data.PkIndexEntry> entries) {
        CacheResidents.residentPk(IocContainer.get(org.ccg.mpp.cache.UserCache.class), collId, entries);
    }

    protected static void injectPages(String collId, List<org.ccg.mpp.data.admin.AdminPageEntry> pageList)
            throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, List<org.ccg.mpp.data.admin.AdminPageEntry>>>() {
        };
        final var adminCache = IocContainer.get(org.ccg.mpp.cache.AdminCache.class);
        final var pages = TestUtils.getPrivateField(TestUtils.pageCacheOf(adminCache), "pages", type);
        pages.put(collId, pageList);
    }
}

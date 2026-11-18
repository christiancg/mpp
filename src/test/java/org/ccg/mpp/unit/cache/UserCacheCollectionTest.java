package org.ccg.mpp.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import org.ccg.mpp.cache.UserCache;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class UserCacheCollectionTest {
    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    @Test
    public void handles_collection_not_in_cache() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        String dbName = TestGlobals.DB;
        String collName = TestGlobals.COLL;
        PkIndexEntry idxEntry = new PkIndexEntry(dbName, collName, "testValue", 0, 100, 0);
        UserCache cache = new UserCache();
        FileSystem fsMock = mock(FileSystem.class);
        Field fsField = UserCache.class.getDeclaredField("fs");
        fsField.setAccessible(true);
        fsField.set(cache, fsMock);

        DbEntry expectedEntry = new DbEntry();
        expectedEntry.setDatabaseName(dbName);
        expectedEntry.setCollectionName(collName);
        expectedEntry.set_id("testValue");

        when(fsMock.getById(idxEntry)).thenReturn(expectedEntry);

        DbEntry result = cache.getById(dbName, collName, idxEntry);

        assertEquals(expectedEntry, result);
    }
}

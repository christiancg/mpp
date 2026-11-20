package org.ccg.mpp.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class LocalDigestRetractTest {
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private File tombstones;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        tombstones = new File(TestGlobals.PATH + "/" + TestGlobals.PROJECT + "/" + TestGlobals.COLL + "/"
                + TestGlobals.COLL + "-tombstones.idx");
    }

    @AfterEach
    public void tearDown() throws Exception {
        assertTrue(tombstones.setWritable(true) || !tombstones.exists());
        fs.tombstones().view(TestGlobals.PROJECT, TestGlobals.COLL);
        TestUtils.standardTearDown();
    }

    @Test
    public void aHeldBackRetractDoesNotOutliveTheLiveRow() throws Exception {
        final var live = List.of(new PkIndexEntry(TestGlobals.PROJECT, TestGlobals.COLL, "kept", 0L, 10L, 0L, 3L));
        fs.tombstones().append(TestGlobals.PROJECT, TestGlobals.COLL, "kept", 7L);
        assertTrue(
                LocalDigest.outlives(live, "kept",
                        fs.tombstones().view(TestGlobals.PROJECT, TestGlobals.COLL).versionOf("kept")),
                "an unretracted tombstone newer than the row wins");
        assertTrue(tombstones.setWritable(false));

        fs.tombstones().retract(TestGlobals.PROJECT, TestGlobals.COLL, "kept", 7L);

        final var view = fs.tombstones().view(TestGlobals.PROJECT, TestGlobals.COLL);
        assertEquals(0L, view.versionOf("kept"));
        assertFalse(LocalDigest.outlives(live, "kept", view.versionOf("kept")),
                "the taken-back delete never deletes the live document");
        assertTrue(view.isEmpty());
    }
}

package org.ccg.mpp.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.util.List;
import java.util.function.Function;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ex.InvalidPathSegmentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FilePathsContainmentTest {
    private static final String ROOT = "root";
    private final FilePaths paths = new FilePaths();

    @BeforeEach
    public void setUp() {
        paths.useDbPath(ROOT);
    }

    private List<Function<String, File>> everyBuilder() {
        return List.of(paths::projectFolder, paths::rawProjectFolder, name -> paths.collectionFolder("shop", name),
                name -> paths.collectionFolder(name, "orders"), name -> paths.collectionPage(name, "orders", 1),
                name -> paths.collectionPage("shop", name, 1),
                name -> paths.indexFile("shop", "orders", name, "String"),
                name -> paths.indexFile(name, "orders", "field", "String"), name -> paths.procedureFile("shop", name),
                name -> paths.procedureFile(name, "nightly"), name -> paths.scheduleFile("shop", name),
                name -> paths.scheduleFile(name, "nightly"), name -> paths.schemaFile("shop", name),
                name -> paths.schemaFile(name, "orders"), name -> paths.triggersFile("shop", name),
                name -> paths.triggersFile(name, "orders"), name -> paths.pkIndexFile(name, "orders"),
                name -> paths.tombstoneFile("shop", name));
    }

    @Test
    public void rejectsDotDotAsProject() {
        assertThrows(InvalidPathSegmentException.class, () -> paths.projectFolder(".."));
        assertThrows(InvalidPathSegmentException.class, () -> paths.rawProjectFolder(".."));
    }

    @Test
    public void rejectsDotAsCollection() {
        assertThrows(InvalidPathSegmentException.class, () -> paths.collectionFolder("shop", "."));
    }

    @Test
    public void rejectsSeparatorsInEveryBuilder() {
        for (final var builder : everyBuilder()) {
            for (final var bad : List.of("a/b", "a\\b", "../x", "")) {
                assertThrows(InvalidPathSegmentException.class, () -> builder.apply(bad), bad);
            }
        }
    }

    @Test
    public void rejectsNul() {
        assertThrows(InvalidPathSegmentException.class, () -> paths.projectFolder("a\0b"));
        assertThrows(InvalidPathSegmentException.class, () -> paths.indexFile("shop", "orders", "a\0b", "String"));
    }

    @Test
    public void rejectsNull() {
        assertThrows(InvalidPathSegmentException.class, () -> paths.projectFolder(null));
        assertThrows(InvalidPathSegmentException.class, () -> paths.procedureFile("shop", null));
    }

    @Test
    public void acceptsAdminPagesAndTranslatesIt() {
        assertEquals(
                new File(new File(ROOT).getAbsolutePath() + Globals.FILE_SEPARATOR + Globals.ADMIN_PROJECT_NAME
                        + Globals.FILE_SEPARATOR + Globals.ADMIN_PAGES_FOLDER),
                paths.projectFolder(Globals.ADMIN_PAGES_PROJECT_NAME));
    }

    @Test
    public void acceptsOrdinaryNames() {
        for (final var builder : everyBuilder()) {
            assertNotNull(builder.apply("orders_1"));
        }
        assertNotNull(paths.indexFile("shop", "orders", "a.b", "String"));
        assertEquals(new File(new File(ROOT).getAbsolutePath() + Globals.FILE_SEPARATOR + "shop"),
                paths.projectFolder("shop"));
    }

    @Test
    public void indexBuildingMarkersRefuseASeparatorBearingFieldName() {
        final var markers = new IndexMarkers(paths);
        for (final var bad : List.of("../escape", "a/b", "a\\b", "a\0b", "")) {
            assertThrows(InvalidPathSegmentException.class, () -> markers.mark("shop", "orders", bad), bad);
        }
    }
}

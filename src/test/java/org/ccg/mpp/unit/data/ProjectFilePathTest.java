package org.ccg.mpp.unit.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.ccg.mpp.data.ProjectFilePath;
import org.ccg.mpp.ex.InvalidFilePathException;
import org.junit.jupiter.api.Test;

public class ProjectFilePathTest {
    @Test
    public void test_leading_slash_dot_and_dot_dot_normalise() throws Exception {
        assertEquals("/a/c", ProjectFilePath.parse("a/./b/../c").toString());
        assertEquals(ProjectFilePath.parse("/a/c"), ProjectFilePath.parse("a//c/"));
        assertEquals("/", ProjectFilePath.parse("").toString());
        assertTrue(ProjectFilePath.parse("/").isRoot());
        assertTrue(ProjectFilePath.parse("a/..").isRoot());
    }

    @Test
    public void test_climbing_above_the_root_is_refused() {
        assertThrows(InvalidFilePathException.class, () -> ProjectFilePath.parse("../x"));
        assertThrows(InvalidFilePathException.class, () -> ProjectFilePath.parse("/a/../../x"));
        assertThrows(InvalidFilePathException.class, () -> ProjectFilePath.parse(null));
    }

    @Test
    public void test_forbidden_and_control_characters_are_refused() {
        for (final var bad : List.of("a\\b", "a:b", "a*", "a?", "a\"", "a<", "a>", "a|b", "100%", "a#b", "\u0001x",
                "café")) {
            assertThrows(InvalidFilePathException.class, () -> ProjectFilePath.parse("/dir/" + bad), bad);
        }
    }

    @Test
    public void test_trailing_dots_edge_spaces_and_device_names_are_refused() {
        for (final var bad : List.of("a.", "...", " a", "a ", "CON", "nul.txt", "COM1.js", "lpt9")) {
            assertThrows(InvalidFilePathException.class, () -> ProjectFilePath.parse(bad), bad);
        }
    }

    @Test
    public void test_dot_files_and_well_known_are_accepted() throws Exception {
        assertEquals("/.well-known/x", ProjectFilePath.parse(".well-known/x").toString());
        assertEquals("/.env", ProjectFilePath.parse("/.env").toString());
        assertEquals("/my photo (1).png", ProjectFilePath.parse("my photo (1).png").toString());
        assertEquals("/console.log", ProjectFilePath.parse("console.log").toString());
    }

    @Test
    public void test_length_segment_count_and_segment_length_are_bounded() throws Exception {
        final var segment = "a".repeat(255);
        assertEquals(256, ProjectFilePath.parse(segment).toString().length());
        assertThrows(InvalidFilePathException.class, () -> ProjectFilePath.parse("a".repeat(256)));
        final var deep = "/a".repeat(32);
        assertEquals(32, ProjectFilePath.parse(deep).segments().size());
        assertThrows(InvalidFilePathException.class, () -> ProjectFilePath.parse(deep + "/a"));
        final var long1024 = "/" + "b".repeat(200) + ("/" + "b".repeat(200)).repeat(4) + "/" + "c".repeat(18);
        assertEquals(1024, ProjectFilePath.parse(long1024).toString().length());
        assertThrows(InvalidFilePathException.class, () -> ProjectFilePath.parse(long1024 + "c"));
        assertThrows(InvalidFilePathException.class, () -> ProjectFilePath.parse("/".repeat(5000)));
    }

    @Test
    public void test_parent_name_child_within_and_rebased() throws Exception {
        final var path = ProjectFilePath.parse("/a/b/File.TXT");
        assertEquals("/a/b", path.parent().toString());
        assertEquals("File.TXT", path.name());
        assertEquals("file.txt", path.foldedName());
        assertEquals("/a/b/c", path.parent().child("c").toString());
        assertTrue(path.isWithin(ProjectFilePath.parse("/a")));
        assertTrue(path.isWithin(path));
        assertTrue(path.isWithin(ProjectFilePath.root()));
        assertFalse(ProjectFilePath.parse("/ab").isWithin(ProjectFilePath.parse("/a")));
        assertEquals("/z/b/File.TXT",
                path.rebased(ProjectFilePath.parse("/a"), ProjectFilePath.parse("/z")).toString());
        assertEquals(List.of("/a", "/a/b"), path.ancestors().stream().map(ProjectFilePath::toString).toList());
        assertEquals("/a/b/", path.parent().subtreeLowerBound());
        assertEquals("/", ProjectFilePath.root().subtreeLowerBound());
        assertEquals("", ProjectFilePath.root().name());
        assertTrue(ProjectFilePath.root().parent().isRoot());
    }

    @Test
    public void test_stored_form_round_trips_and_orders_by_text() throws Exception {
        final var path = ProjectFilePath.parse("x/y");
        assertEquals(path, ProjectFilePath.ofStored(path.toString()));
        assertEquals(path.hashCode(), ProjectFilePath.ofStored("/x/y").hashCode());
        assertTrue(ProjectFilePath.ofStored("/").isRoot());
        assertTrue(ProjectFilePath.parse("/a").compareTo(ProjectFilePath.parse("/b")) < 0);
        assertNotEquals(ProjectFilePath.parse("/x/z"), path);
    }
}

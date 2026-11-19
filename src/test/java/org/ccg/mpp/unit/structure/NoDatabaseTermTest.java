package org.ccg.mpp.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

public class NoDatabaseTermTest {
    static final Path ALLOWANCE = Path.of("config/database-term-allowlist.txt");
    private static final String SELF = "src/test/java/org/ccg/mpp/unit/structure/NoDatabaseTermTest.java";
    private static final List<Path> ROOTS = Stream.of("src", "clients", "docs", "test_utils", "config", ".github")
            .map(Path::of).toList();
    private static final List<String> TOP_LEVEL_FILES = List.of("README.md", "QUICKSTART.md", "CONTRIBUTING.md",
            "SECURITY.md", "Dockerfile", "docker-entrypoint.sh", "healthcheck.sh", "pom.xml", "mpp.cfg");
    private static final Set<String> BUILD_OUTPUT_FOLDERS = Set.of("target", "node_modules", "bin", "obj", "dist",
            "coverage", "results", "artifacts", "__pycache__");
    private static final Pattern TERM = Pattern.compile("database", Pattern.CASE_INSENSITIVE);
    private static final String FILE = "docs/a.md";

    static boolean scanned(String relativePath) {
        if (relativePath.equals(SELF) || relativePath.equals(ALLOWANCE.toString().replace('\\', '/'))) {
            return false;
        }
        final var segments = relativePath.split("/");
        for (var i = 0; i < segments.length - 1; i++) {
            final var folder = segments[i];
            if (BUILD_OUTPUT_FOLDERS.contains(folder) || i > 0 && folder.startsWith(".")) {
                return false;
            }
        }
        return !segments[segments.length - 1].startsWith(".");
    }

    static int mentions(String text) {
        return (int) TERM.matcher(text).results().count();
    }

    static Map<String, Integer> currentMentions() throws IOException {
        final var counts = new TreeMap<String, Integer>();
        for (final var name : TOP_LEVEL_FILES) {
            count(Path.of(name), counts);
        }
        for (final var root : ROOTS) {
            if (Files.isDirectory(root)) {
                try (Stream<Path> paths = Files.walk(root)) {
                    paths.filter(Files::isRegularFile).filter(path -> scanned(relative(path)))
                            .forEach(path -> count(path, counts));
                }
            }
        }
        return counts;
    }

    private static String relative(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void count(Path path, Map<String, Integer> counts) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            final var found = mentions(Files.readString(path, StandardCharsets.ISO_8859_1));
            if (found > 0) {
                counts.put(relative(path), found);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> problems(Map<String, Integer> current, Map<String, Integer> allowance) {
        final var problems = new ArrayList<String>();
        final var paths = new TreeSet<>(current.keySet());
        paths.addAll(allowance.keySet());
        for (final var path : paths) {
            final int count = current.getOrDefault(path, 0);
            final int allowed = allowance.getOrDefault(path, 0);
            if (count > allowed) {
                problems.add(path + ": " + count + " mentions, allowance " + allowed);
            } else if (count < allowed) {
                problems.add(count == 0
                        ? path + ": now 0, delete its allowance line"
                        : path + ": now " + count + ", lower its allowance line to " + count);
            }
        }
        return problems;
    }

    @Test
    public void test_no_file_mentions_the_term_beyond_its_allowance_and_the_allowance_is_exact() throws Exception {
        final var current = currentMentions();
        if (!Files.exists(ALLOWANCE)) {
            fail(ALLOWANCE + " is missing; create it with exactly these lines:\n" + NoNewCommentsTest.render(current));
        }
        final var problems = problems(current, NoNewCommentsTest.read(ALLOWANCE));
        assertTrue(problems.isEmpty(), "MPP hosts projects, not databases; " + ALLOWANCE
                + " lists the only mentions left and only goes down:\n" + String.join("\n", problems));
    }

    @Test
    public void test_mentions_are_counted_whatever_their_case() {
        assertEquals(3, mentions("Database, DATABASE and databases"));
        assertEquals(0, mentions("a project, its collections and the db module"));
    }

    @Test
    public void test_a_new_file_mentioning_the_term_is_a_problem() {
        assertEquals(List.of(FILE + ": 1 mentions, allowance 0"), problems(Map.of(FILE, 1), Map.of()));
    }

    @Test
    public void test_a_file_below_its_allowance_must_lower_it() {
        assertEquals(List.of(FILE + ": now 1, lower its allowance line to 1"),
                problems(Map.of(FILE, 1), Map.of(FILE, 2)));
        assertEquals(List.of(FILE + ": now 0, delete its allowance line"), problems(Map.of(), Map.of(FILE, 2)));
    }

    @Test
    public void test_matching_counts_have_no_problems() {
        assertTrue(problems(Map.of(FILE, 2), Map.of(FILE, 2)).isEmpty());
    }

    @Test
    public void test_build_output_and_hidden_files_are_not_scanned() {
        assertTrue(scanned("clients/typescript/src/project.ts"));
        assertTrue(scanned(".github/workflows/sanity_check.yml"));
        assertFalse(scanned("clients/typescript/node_modules/pkg/index.js"));
        assertFalse(scanned("clients/java/mpp-client/target/classes/A.class"));
        assertFalse(scanned("clients/python/.mypy_cache/cache.json"));
        assertFalse(scanned("clients/java/.flattened-pom.xml"));
        assertFalse(scanned(SELF));
        assertFalse(scanned(ALLOWANCE.toString()));
    }
}

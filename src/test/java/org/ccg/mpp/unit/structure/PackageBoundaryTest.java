package org.ccg.mpp.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.ccg.mpp.unit.structure.JavaSources.JavaSourceFile;
import org.junit.jupiter.api.Test;

public class PackageBoundaryTest {
    private static final String SIMPLEJS = "org.ccg.mpp.simplejs";
    private static final String SIMPLEJS_HOST = "org.ccg.mpp.simplejs.host";
    private static final String EJSON = "org.ccg.mpp.ejson";
    private static final Pattern SERVER_LAYER_REFERENCE = Pattern
            .compile("\\borg\\.ccg\\.mpp\\.(ops|cache|conn|ioc)\\.[\\w.]+");
    private static final Pattern SIMPLEJS_REFERENCE = Pattern.compile("\\borg\\.ccg\\.mpp\\.simplejs\\.[\\w.]+");
    private static final String REGEX_ENGINE = "org.ccg.mpp.simplejs.internal.regex.";
    private static final String SYNTAX_ERROR = "org.ccg.mpp.simplejs.exceptions.SyntaxErrorException";
    private static final String CLIENT = "org.ccg.mpp.client";
    private static final String ENTRY_POINT = "src/main/java/org/ccg/mpp/Main.java";
    private static final Pattern CLIENT_FORBIDDEN_REFERENCE = Pattern
            .compile("\\borg\\.ccg\\.mpp\\.(?!(client|ejson|ioc|utils|config\\.Globals|config\\.ConfigKey)\\b)[\\w.]+");
    private static final Pattern CLIENT_REFERENCE = Pattern.compile("\\borg\\.ccg\\.mpp\\.client\\b[\\w.]*");
    private static final Pattern PROJECT_REFERENCE = Pattern.compile("\\borg\\.ccg\\.mpp\\.[\\w.]+");
    private static final String METRICS = "org.ccg.mpp.metrics";
    private static final Set<String> ENTRY_POINT_REFERENCES = Set.of("org.ccg.mpp.client.ClientMain",
            "org.ccg.mpp.ServerMain");

    static Set<String> simplejsServerLayerReferences(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (file.isInPackage(SIMPLEJS) && !file.isInPackage(SIMPLEJS_HOST)) {
                final var matcher = SERVER_LAYER_REFERENCE.matcher(file.text().code());
                while (matcher.find()) {
                    found.add(file.relativePath() + " -> " + matcher.group());
                }
            }
        }
        return found;
    }

    static Set<String> ejsonSimplejsReferencesBeyondTheRegexEngine(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (file.isInPackage(EJSON)) {
                final var matcher = SIMPLEJS_REFERENCE.matcher(file.text().code());
                while (matcher.find()) {
                    final var reference = matcher.group();
                    if (!reference.startsWith(REGEX_ENGINE) && !reference.equals(SYNTAX_ERROR)) {
                        found.add(file.relativePath() + " -> " + reference);
                    }
                }
            }
        }
        return found;
    }

    static Set<String> clientReferencesBeyondItsPackages(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (file.isInPackage(CLIENT)) {
                final var matcher = CLIENT_FORBIDDEN_REFERENCE.matcher(file.text().code());
                while (matcher.find()) {
                    found.add(file.relativePath() + " -> " + matcher.group());
                }
            }
        }
        return found;
    }

    static Set<String> clientReachedFromOutside(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (!file.isInPackage(CLIENT) && !file.relativePath().equals(ENTRY_POINT)) {
                final var matcher = CLIENT_REFERENCE.matcher(file.text().code());
                while (matcher.find()) {
                    found.add(file.relativePath() + " -> " + matcher.group());
                }
            }
        }
        return found;
    }

    static Set<String> metricsProjectReferences(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (file.isInPackage(METRICS)) {
                final var matcher = PROJECT_REFERENCE.matcher(file.text().code());
                while (matcher.find()) {
                    if (!matcher.group().startsWith(METRICS + ".") && !matcher.group().equals(METRICS)) {
                        found.add(file.relativePath() + " -> " + matcher.group());
                    }
                }
            }
        }
        return found;
    }

    static Set<String> entryPointReferencesBeyondTheDispatch(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (file.relativePath().equals(ENTRY_POINT)) {
                final var matcher = PROJECT_REFERENCE.matcher(file.text().code());
                while (matcher.find()) {
                    if (!ENTRY_POINT_REFERENCES.contains(matcher.group())) {
                        found.add(file.relativePath() + " -> " + matcher.group());
                    }
                }
            }
        }
        return found;
    }

    @Test
    public void test_client_reaches_only_its_allowed_packages() {
        final var found = clientReferencesBeyondItsPackages(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(), "the client may use only ejson, ioc, utils, config.Globals and config.ConfigKey,"
                + " since Configuration and Logger create ./db and ./logs and write to stdout: " + found);
    }

    @Test
    public void test_nothing_but_main_reaches_the_client() {
        final var found = clientReachedFromOutside(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(), "only Main may dispatch to the client: " + found);
    }

    @Test
    public void test_main_only_dispatches() {
        final var found = entryPointReferencesBeyondTheDispatch(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(), "Main must only choose between ClientMain and ServerMain: " + found);
    }

    @Test
    public void test_a_synthetic_client_class_reaching_configuration_is_caught() {
        final var client = JavaSources.parse("Tool.java", """
                package org.ccg.mpp.client;
                import org.ccg.mpp.config.Configuration;
                import org.ccg.mpp.config.Globals;
                import org.ccg.mpp.config.ConfigKey;
                import org.ccg.mpp.ejson.EJson;
                import org.ccg.mpp.log.Logger;
                class Tool {}
                """);
        assertEquals(Set.of("Tool.java -> org.ccg.mpp.config.Configuration", "Tool.java -> org.ccg.mpp.log.Logger"),
                clientReferencesBeyondItsPackages(List.of(client)));
    }

    @Test
    public void test_a_synthetic_server_class_reaching_the_client_is_caught_but_main_is_not() {
        final var server = JavaSources.parse("src/main/java/org/ccg/mpp/ops/Op.java",
                "package org.ccg.mpp.ops;\nimport org.ccg.mpp.client.Dump;\nclass Op {}\n");
        final var main = JavaSources.parse(ENTRY_POINT,
                "package org.ccg.mpp;\nimport org.ccg.mpp.client.ClientMain;\nclass Main {}\n");
        assertEquals(Set.of("src/main/java/org/ccg/mpp/ops/Op.java -> org.ccg.mpp.client.Dump"),
                clientReachedFromOutside(List.of(server, main)));
    }

    @Test
    public void test_a_synthetic_main_reaching_the_server_layers_is_caught() {
        final var main = JavaSources.parse(ENTRY_POINT, """
                package org.ccg.mpp;
                import org.ccg.mpp.client.ClientMain;
                import org.ccg.mpp.config.Configuration;
                class Main {}
                """);
        assertEquals(Set.of(ENTRY_POINT + " -> org.ccg.mpp.config.Configuration"),
                entryPointReferencesBeyondTheDispatch(List.of(main)));
    }

    @Test
    public void test_metrics_imports_nothing_from_the_project() {
        final var found = metricsProjectReferences(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(),
                "metrics holds primitives every layer records into, so it may use only java.*: " + found);
    }

    @Test
    public void test_a_synthetic_metrics_class_reaching_the_server_is_caught() {
        final var histogram = JavaSources.parse("Gauge.java", """
                package org.ccg.mpp.metrics;
                import org.ccg.mpp.metrics.Histogram;
                import org.ccg.mpp.ops.ErrorCode;
                class Gauge {}
                """);
        assertEquals(Set.of("Gauge.java -> org.ccg.mpp.ops.ErrorCode"), metricsProjectReferences(List.of(histogram)));
    }

    @Test
    public void test_only_the_simplejs_host_reaches_the_server_layers() {
        final var found = simplejsServerLayerReferences(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(), "simplejs/host is the only package that may import ops/cache/conn/ioc: " + found);
    }

    @Test
    public void test_ejson_takes_only_the_regex_engine_from_simplejs() {
        final var found = ejsonSimplejsReferencesBeyondTheRegexEngine(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(),
                "ejson may use only simplejs.internal.regex and SyntaxErrorException from simplejs: " + found);
    }

    @Test
    public void test_a_synthetic_engine_class_reaching_ops_is_caught_but_the_host_is_not() {
        final var engine = JavaSources.parse("Engine.java",
                "package org.ccg.mpp.simplejs.internal;\nimport org.ccg.mpp.ops.ErrorCode;\nclass Engine {}\n");
        final var host = JavaSources.parse("Host.java",
                "package org.ccg.mpp.simplejs.host;\nimport org.ccg.mpp.ops.ErrorCode;\nclass Host {}\n");
        assertEquals(Set.of("Engine.java -> org.ccg.mpp.ops.ErrorCode"),
                simplejsServerLayerReferences(List.of(engine, host)));
    }

    @Test
    public void test_a_synthetic_ejson_class_reaching_the_interpreter_is_caught() {
        final var codec = JavaSources.parse("Codec.java", """
                package org.ccg.mpp.ejson.validate;
                import org.ccg.mpp.simplejs.internal.regex.RegexParser;
                import org.ccg.mpp.simplejs.exceptions.SyntaxErrorException;
                class Codec { org.ccg.mpp.simplejs.internal.interpreter.Interpreter i; }
                """);
        assertEquals(Set.of("Codec.java -> org.ccg.mpp.simplejs.internal.interpreter.Interpreter"),
                ejsonSimplejsReferencesBeyondTheRegexEngine(List.of(codec)));
    }
}

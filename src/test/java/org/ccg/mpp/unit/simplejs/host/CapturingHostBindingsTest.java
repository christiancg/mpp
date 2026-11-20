package org.ccg.mpp.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.simplejs.host.CapturingHostBindings;
import org.ccg.mpp.simplejs.host.ConsoleCapture;
import org.ccg.mpp.simplejs.host.HostBindings;
import org.ccg.mpp.simplejs.host.ModuleResolver;
import org.ccg.mpp.simplejs.host.NetworkAccess;
import org.ccg.mpp.simplejs.host.ProjectAccess;
import org.ccg.mpp.simplejs.host.ResolvedModule;
import org.ccg.mpp.simplejs.host.ResourceLimits;
import org.junit.jupiter.api.Test;

public class CapturingHostBindingsTest {
    private record FullBindings(JsonObject args, ProjectAccess project, Consumer<String> console, ResourceLimits limits,
            ModuleResolver moduleResolver, NetworkAccess network, ZoneId timeZone, Locale locale,
            boolean strictScriptGoal) implements HostBindings {
    }

    private static FullBindings bindings(Consumer<String> console) {
        return new FullBindings(new JsonObject(), null, console, ResourceLimits.unlimited(),
                (_, _) -> new ResolvedModule("id", "src"), null, ZoneId.of("UTC"), Locale.FRANCE, true);
    }

    @Test
    public void test_delegates_every_other_member() {
        final var delegate = bindings(null);
        final var capture = new ConsoleCapture(10, 100);
        final var wrapped = CapturingHostBindings.wrap(delegate, capture);
        assertSame(delegate.args(), wrapped.args());
        assertNull(wrapped.project());
        assertSame(delegate.limits(), wrapped.limits());
        assertTrue(wrapped.strictScriptGoal());
        assertSame(delegate.moduleResolver(), wrapped.moduleResolver());
        assertNull(wrapped.network());
        assertEquals(ZoneId.of("UTC"), wrapped.timeZone());
        assertEquals(Locale.FRANCE, wrapped.locale());
    }

    @Test
    public void test_console_with_no_host_sink_is_the_capture() {
        final var capture = new ConsoleCapture(10, 100);
        final var wrapped = CapturingHostBindings.wrap(bindings(null), capture);
        assertSame(capture, wrapped.console());
        wrapped.console().accept("a");
        assertEquals(List.of("a"), capture.lines());
    }

    @Test
    public void test_console_tees_into_both() {
        final var sink = new ArrayList<String>();
        final var capture = new ConsoleCapture(10, 100);
        final var wrapped = CapturingHostBindings.wrap(bindings(sink::add), capture);
        wrapped.console().accept("a");
        wrapped.console().accept("b");
        assertEquals(List.of("a", "b"), sink);
        assertEquals(List.of("a", "b"), capture.lines());
    }
}

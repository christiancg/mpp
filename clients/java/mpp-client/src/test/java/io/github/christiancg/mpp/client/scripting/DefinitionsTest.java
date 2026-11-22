package io.github.christiancg.mpp.client.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.mpp.client.Document;
import io.github.christiancg.mpp.client.admin.DataPermission;
import io.github.christiancg.mpp.client.admin.GlobalPermission;
import io.github.christiancg.mpp.client.admin.Permissions;
import io.github.christiancg.mpp.client.admin.ScriptPermission;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class DefinitionsTest {
    @Test
    public void test_a_trigger_needs_an_event_and_exposes_what_was_set() {
        assertThrows(IllegalArgumentException.class, () -> TriggerDefinition.builder("t", "p").build());
        final var trigger = TriggerDefinition.builder("t", "p").events(TriggerEvent.DELETED)
                .timing(TriggerTiming.BEFORE).mode(TriggerMode.BATCH).allowCascade(true).enabled(false).ifVersion(4)
                .build();
        assertEquals("t", trigger.name());
        assertEquals("p", trigger.procedureName());
        assertEquals(List.of(TriggerEvent.DELETED), trigger.events());
        assertEquals(TriggerTiming.BEFORE, trigger.timing().orElseThrow());
        assertEquals(TriggerMode.BATCH, trigger.mode().orElseThrow());
        assertTrue(trigger.allowCascade().orElseThrow());
        assertEquals(false, trigger.enabled().orElseThrow());
        assertEquals(OptionalLong.of(4), trigger.ifVersion());
        assertTrue(TriggerDefinition.builder("t", "p").events(TriggerEvent.CREATED).build().ifVersion().isEmpty());
    }

    @Test
    public void test_a_schedule_takes_exactly_one_of_cron_and_interval() {
        assertThrows(IllegalArgumentException.class, () -> ScheduleDefinition.builder("s", "p").build());
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleDefinition.builder("s", "p").cron("* * * * *").interval(Duration.ofMinutes(1)).build());
        final var schedule = ScheduleDefinition.builder("s", "p").interval(Duration.ofMinutes(1))
                .args(Document.of("a", 1)).timeout(Duration.ofSeconds(5)).description("d").enabled(true).ifVersion(2)
                .build();
        assertEquals("s", schedule.name());
        assertEquals("p", schedule.procedureName());
        assertTrue(schedule.cron().isEmpty());
        assertEquals(Duration.ofMinutes(1), schedule.interval().orElseThrow());
        assertEquals(OptionalLong.of(1), schedule.args().orElseThrow().getLong("a"));
        assertEquals(Duration.ofSeconds(5), schedule.timeout().orElseThrow());
        assertEquals("d", schedule.description().orElseThrow());
        assertTrue(schedule.enabled().orElseThrow());
        assertEquals(OptionalLong.of(2), schedule.ifVersion());
        assertTrue(ScheduleDefinition.builder("s", "p").cron("* * * * *").build().ifVersion().isEmpty());
    }

    @Test
    public void test_an_entrypoint_takes_exactly_one_of_procedure_name_and_procedure() {
        final var inline = ProcedureDefinition.builder("p", "return 1;").build();
        final var neither = assertThrows(IllegalArgumentException.class,
                () -> EntrypointDefinition.builder("e", EntrypointMethod.GET, "/e").build());
        assertEquals("an entrypoint takes exactly one of procedureName and procedure", neither.getMessage());
        assertThrows(IllegalArgumentException.class, () -> EntrypointDefinition.builder("e", EntrypointMethod.GET, "/e")
                .procedureName("p").procedure(inline).build());
    }

    @Test
    public void test_an_inline_procedure_takes_no_if_version() {
        final var versioned = ProcedureDefinition.builder("p", "return 1;").ifVersion(2).build();
        final var refused = assertThrows(IllegalArgumentException.class,
                () -> EntrypointDefinition.builder("e", EntrypointMethod.POST, "/e").procedure(versioned).build());
        assertEquals("an inline procedure takes no ifVersion", refused.getMessage());
    }

    @Test
    public void test_an_entrypoint_definition_exposes_its_fields() {
        final var inline = ProcedureDefinition.builder("p", "return 1;").build();
        final var entrypoint = EntrypointDefinition.builder("e", EntrypointMethod.QUERY, "/search/{term}")
                .procedure(inline).timeout(Duration.ofSeconds(5)).description("d").enabled(false).ifVersion(3).build();
        assertEquals("e", entrypoint.name());
        assertEquals(EntrypointMethod.QUERY, entrypoint.method());
        assertEquals("/search/{term}", entrypoint.path());
        assertTrue(entrypoint.procedureName().isEmpty());
        assertEquals(inline, entrypoint.procedure().orElseThrow());
        assertEquals(Duration.ofSeconds(5), entrypoint.timeout().orElseThrow());
        assertEquals("d", entrypoint.description().orElseThrow());
        assertEquals(false, entrypoint.enabled().orElseThrow());
        assertEquals(OptionalLong.of(3), entrypoint.ifVersion());
        final var byName = EntrypointDefinition.builder("e", EntrypointMethod.GET, "/e").procedureName("p").build();
        assertEquals("p", byName.procedureName().orElseThrow());
        assertTrue(byName.procedure().isEmpty());
        assertTrue(byName.ifVersion().isEmpty());
    }

    @Test
    public void test_a_procedure_exposes_what_was_set() {
        final var procedure = ProcedureDefinition.builder("p", "return 1;").description("d").enabled(false).ifVersion(9)
                .build();
        assertEquals("p", procedure.name());
        assertEquals("return 1;", procedure.script());
        assertEquals("d", procedure.description().orElseThrow());
        assertEquals(false, procedure.enabled().orElseThrow());
        assertEquals(OptionalLong.of(9), procedure.ifVersion());
        assertTrue(ProcedureDefinition.builder("p", "s").build().ifVersion().isEmpty());
    }

    @Test
    public void test_permissions_are_immutable_snapshots() {
        final var permissions = Permissions.builder().admin(true).global(GlobalPermission.DROP_PROJECT)
                .project("shop", DataPermission.READ).collection("shop", "orders", DataPermission.READ_WRITE)
                .scripts("shop", ScriptPermission.NONE).build();
        assertTrue(permissions.admin());
        assertEquals(Set.of(GlobalPermission.DROP_PROJECT), permissions.global());
        assertEquals(Map.of("shop", DataPermission.READ), permissions.projects());
        assertEquals(Map.of("shop|orders", DataPermission.READ_WRITE), permissions.collections());
        assertEquals(Map.of("shop", ScriptPermission.NONE), permissions.scripts());
        assertUnmodifiable(permissions.projects());
    }

    private static void assertUnmodifiable(Map<?, ?> map) {
        assertThrows(UnsupportedOperationException.class, map::clear);
    }
}

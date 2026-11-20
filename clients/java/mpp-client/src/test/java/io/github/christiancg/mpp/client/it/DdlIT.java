package io.github.christiancg.mpp.client.it;

import static io.github.christiancg.mpp.client.query.Filters.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.mpp.client.Document;
import io.github.christiancg.mpp.client.error.ConflictException;
import io.github.christiancg.mpp.client.error.MppException;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

public class DdlIT extends ServerIT {
    @Test
    public void test_projects_and_collections_are_created_listed_and_dropped() {
        final var project = client.project("ddl_it");
        project.create();
        assertTrue(client.listProjects().contains("ddl_it"));
        final var orders = project.collection("orders");
        orders.create();
        assertEquals(List.of("orders"), project.listCollections());
        orders.drop();
        assertTrue(project.listCollections().isEmpty());
        project.drop();
        assertFalse(client.listProjects().contains("ddl_it"));
    }

    @Test
    public void test_names_differing_only_by_case_and_reserved_names_are_refused() {
        client.project("Case_db").create();
        final var error = assertThrows(ConflictException.class, () -> client.project("case_DB").create());
        assertEquals("409-11", error.rawCode().orElseThrow());
        assertThrows(MppException.class, () -> client.project("Admin").create());
        assertThrows(IllegalArgumentException.class, () -> client.project("no"));
    }

    @Test
    public void test_indexes_create_many_recreate_drop_and_reindex() {
        final var project = client.project("index_it");
        project.create();
        final var users = project.collection("users");
        users.create();
        users.saveAll(List.of(Document.of("_id", "u1", "email", "a@x", "status", "on"),
                Document.of("_id", "u2", "email", "b@x", "status", "off")));
        users.indexes().create("email", "status");
        users.indexes().create("email");
        assertEquals(1, users.query().filter(field("email").eq("A@X")).count());
        assertEquals(List.of("email"), users.indexes().reindex("email").rebuiltFields());
        assertEquals(2, users.indexes().reindex().rebuiltFields().size());
        final var primary = users.indexes().reindexPrimaryKey();
        assertEquals(OptionalLong.of(2), primary.primaryKeyEntries());
        users.indexes().drop("status");
        users.indexes().drop("status");
        assertEquals(1, users.query().filter(field("status").eq("on")).count());
    }

    @Test
    public void test_a_schema_is_enforced_until_deleted() {
        final var project = client.project("schema_it");
        project.create();
        final var people = project.collection("people");
        people.create();
        assertTrue(people.schema().get().isEmpty());
        people.schema().save(Document.of("type", "object", "required", List.of("name"), "properties",
                Document.of("name", Document.of("type", "string", "minLength", 1))));
        assertEquals("object", people.schema().get().orElseThrow().getString("type").orElseThrow());
        final var refused = assertThrows(MppException.class, () -> people.save(Document.of("_id", "p1")));
        assertEquals("400-7", refused.rawCode().orElseThrow());
        people.save(Document.of("_id", "p1", "name", "Ana"));
        people.schema().delete();
        people.save(Document.of("_id", "p2"));
        assertTrue(people.findById("p2").isPresent());
    }
}

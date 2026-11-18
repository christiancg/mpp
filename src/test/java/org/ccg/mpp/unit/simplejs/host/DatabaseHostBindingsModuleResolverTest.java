package org.ccg.mpp.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.*;

import org.ccg.mpp.config.ConfigReader;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.simplejs.host.DatabaseHostBindings;
import org.ccg.mpp.simplejs.host.EnforcingDatabaseAccess;
import org.ccg.mpp.simplejs.host.ProcedureModuleResolver;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DatabaseHostBindingsModuleResolverTest {
    private static final Configuration configuration = Configuration.getInstance();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptProcedureImportEnabled", true);
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptProcedureImportEnabled", true);
    }

    private static DatabaseHostBindings hostFor(String scopedDatabase) {
        final var database = new EnforcingDatabaseAccess("alice", null, scopedDatabase);
        return DatabaseHostBindings.of(null, database, null, DatabaseHostBindings.limitsFromConfiguration(), null);
    }

    @Test
    void resolverPresentWhenEnabledAndScoped() {
        assertInstanceOf(ProcedureModuleResolver.class, hostFor(TestGlobals.DB).moduleResolver());
    }

    @Test
    void resolverAbsentWhenKeyDisabled() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptProcedureImportEnabled", false);
        assertNull(hostFor(TestGlobals.DB).moduleResolver());
    }

    @Test
    void resolverAbsentWhenUnscoped() {
        assertNull(hostFor(null).moduleResolver());
    }

    @Test
    void resolverAbsentWithoutDatabaseAccess() {
        final var host = DatabaseHostBindings.of(null, null, null, DatabaseHostBindings.limitsFromConfiguration(),
                null);
        assertNull(host.moduleResolver());
    }

    @Test
    void shippedDefaultEnablesImports() {
        final var configs = ConfigReader.loadConfiguration();
        assertEquals("true", configs.get("scriptProcedureImportEnabled"),
                "the bundled default.cfg is the only source of the enabled-by-default value");
    }
}

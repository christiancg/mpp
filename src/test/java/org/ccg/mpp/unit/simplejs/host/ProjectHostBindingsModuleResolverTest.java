package org.ccg.mpp.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.*;

import org.ccg.mpp.config.ConfigReader;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.simplejs.host.EnforcingProjectAccess;
import org.ccg.mpp.simplejs.host.ProcedureModuleResolver;
import org.ccg.mpp.simplejs.host.ProjectHostBindings;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ProjectHostBindingsModuleResolverTest {
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

    private static ProjectHostBindings hostFor(String scopedProject) {
        final var project = new EnforcingProjectAccess("alice", null, scopedProject);
        return ProjectHostBindings.of(null, project, null, ProjectHostBindings.limitsFromConfiguration(), null);
    }

    @Test
    void resolverPresentWhenEnabledAndScoped() {
        assertInstanceOf(ProcedureModuleResolver.class, hostFor(TestGlobals.PROJECT).moduleResolver());
    }

    @Test
    void resolverAbsentWhenKeyDisabled() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptProcedureImportEnabled", false);
        assertNull(hostFor(TestGlobals.PROJECT).moduleResolver());
    }

    @Test
    void resolverAbsentWhenUnscoped() {
        assertNull(hostFor(null).moduleResolver());
    }

    @Test
    void resolverAbsentWithoutProjectAccess() {
        final var host = ProjectHostBindings.of(null, null, null, ProjectHostBindings.limitsFromConfiguration(), null);
        assertNull(host.moduleResolver());
    }

    @Test
    void shippedDefaultEnablesImports() {
        final var configs = ConfigReader.loadConfiguration();
        assertEquals("true", configs.get("scriptProcedureImportEnabled"),
                "the bundled default.cfg is the only source of the enabled-by-default value");
    }
}

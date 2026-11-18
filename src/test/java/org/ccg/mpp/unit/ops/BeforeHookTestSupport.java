package org.ccg.mpp.unit.ops;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.data.TriggerDefinition;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.CompiledProcedureCache;
import org.ccg.mpp.ops.ProcedureOperationHelper;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;

final class BeforeHookTestSupport {
    static final String ACTOR = "alice";
    private static final Configuration configuration = Configuration.getInstance();
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);

    private BeforeHookTestSupport() {
    }

    static void setUpAll() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    static void tearDownAll() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "beforeHookInstructionBudget",
                ConfigKey.BEFORE_HOOK_INSTRUCTION_BUDGET.defaultValue());
        TestUtils.setPrivateField(configuration, "beforeHookTimeoutMs",
                ConfigKey.BEFORE_HOOK_TIMEOUT_MS.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    static void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "beforeHookInstructionBudget", 200_000L);
        TestUtils.setPrivateField(configuration, "beforeHookTimeoutMs", 2_000L);
        TestUtils.resetClients();
        fs.metadataFiles().deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        for (final var name : fs.metadataFiles().listProcedureNames(TestGlobals.DB)) {
            fs.metadataFiles().deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
        IocContainer.get(CompiledProcedureCache.class).invalidateDatabase(TestGlobals.DB);
    }

    static void installHook(String name, String procedure, String source, EventType... events) throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, procedure, source), ACTOR);
        final var existing = new ArrayList<>(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL));
        existing.add(new TriggerDefinition(name, new LinkedHashSet<>(Set.of(events)), procedure,
                TriggerDefinition.MODE_DOCUMENT, TriggerDefinition.TIMING_BEFORE, false, true, ACTOR, 1L, 1L, 1L,
                ACTOR));
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, existing);
    }

    static UUID newClient() {
        final var socket = mock(Socket.class);
        final var address = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(address);
        when(address.getHostAddress()).thenReturn("127.0.0.1");
        return clientTracker.addClient(socket);
    }

    static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("qty", new JsonNumber(2));
        object.add("price", new JsonNumber(10));
        return object;
    }
}

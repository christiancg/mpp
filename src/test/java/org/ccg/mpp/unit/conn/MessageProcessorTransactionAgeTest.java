package org.ccg.mpp.unit.conn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MessageProcessorTransactionAgeTest {
    private static final String ADMIN = "age_admin";
    private static final long MAX_AGE_MS = 600L;
    private final Configuration config = Configuration.getInstance();
    private ScriptedConnection connection;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        TestUtils.createAdmin(ADMIN);
        TestUtils.setPrivateField(config, "transactionMaxAgeMs", MAX_AGE_MS);
        connection = new ScriptedConnection();
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
        TestUtils.setPrivateField(config, "transactionMaxAgeMs", ConfigKey.TRANSACTION_MAX_AGE_MS.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static boolean collectionIsWritable() throws InterruptedException {
        final var locks = IocContainer.get(ResourceLocking.class);
        final var free = new AtomicBoolean();
        final var probe = Thread.ofPlatform().start(() -> {
            free.set(locks.tryLockWrite(TestGlobals.PROJECT, TestGlobals.COLL));
            if (free.get()) {
                locks.releaseWrite(TestGlobals.PROJECT, TestGlobals.COLL);
            }
        });
        probe.join(5000);
        return free.get();
    }

    private static final String FIND_AGED = "{\"type\":\"FIND_BY_ID\",\"projectName\":\"" + TestGlobals.PROJECT
            + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"_id\":\"aged\"}";

    @Test
    @SuppressWarnings("BusyWait")
    public void test_a_busy_transaction_is_rolled_back_at_its_maximum_age() throws Exception {
        connection.start();
        assertTrue(connection
                .exchange("{\"type\":\"AUTHENTICATE\",\"username\":\"" + ADMIN + "\",\"password\":\"password123\"}")
                .contains("\"OK\""));
        assertTrue(connection.exchange("{\"type\":\"START_TRANSACTION\"}").contains("\"OK\""));
        assertTrue(
                connection
                        .exchange("{\"type\":\"SAVE\",\"projectName\":\"" + TestGlobals.PROJECT
                                + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"object\":{\"_id\":\"aged\"}}")
                        .contains("\"OK\""));
        assertFalse(collectionIsWritable());

        final var deadline = System.nanoTime() + 5_000_000_000L;
        var answer = "";
        while (System.nanoTime() < deadline && !answer.contains("408-5")) {
            Thread.sleep(100);
            answer = connection.exchange(FIND_AGED);
        }

        assertTrue(answer.contains("408-5"), answer);
        assertTrue(collectionIsWritable());
        assertTrue(connection.exchange(FIND_AGED).contains("404-2"));
    }
}

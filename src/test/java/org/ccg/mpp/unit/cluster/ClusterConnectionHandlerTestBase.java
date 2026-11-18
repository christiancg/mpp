package org.ccg.mpp.unit.cluster;

import org.ccg.mpp.cluster.PeerConnectionPool;
import org.ccg.mpp.cluster.msg.ClusterMessage;
import org.ccg.mpp.cluster.msg.ClusterMessageType;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.req.RequestParser;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.test.ClusterTestHarness;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public abstract class ClusterConnectionHandlerTestBase {
    protected static final long ACK_TIMEOUT_MS = 30000L;
    protected static final long SHORT_ACK_TIMEOUT_MS = 3000L;
    protected final ClusterTestHarness cluster = new ClusterTestHarness();
    protected final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    protected final PeerConnectionPool pool = new PeerConnectionPool();
    protected final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    protected final EJson eJson = IocContainer.get(EJson.class);
    protected final Configuration config = Configuration.getInstance();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cluster.start(true, ACK_TIMEOUT_MS);
        cluster.configureMembership(1, ClusterTestHarness.node("self", cluster.serverPort()));
    }

    @AfterEach
    public void tearDown() throws Exception {
        pool.closeAll();
        cluster.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    protected ClusterMessage envelope(ClusterMessageType type) {
        final var message = new ClusterMessage();
        message.setType(type);
        message.setSecret(ClusterTestHarness.SECRET);
        message.setActingUser(ClusterTestHarness.ACTING_ADMIN);
        return message;
    }

    protected OperationResponse findById(String id) {
        final var json = "{\"type\":\"FIND_BY_ID\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"_id\":\"" + id + "\"}";
        return processor.processMessage(RequestParser.parseRequest(json));
    }
}

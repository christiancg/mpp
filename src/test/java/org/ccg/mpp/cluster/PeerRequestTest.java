package org.ccg.mpp.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.ccg.mpp.cluster.membership.MembershipService;
import org.ccg.mpp.cluster.msg.ClusterMessageType;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.ClusterTestHarness;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Lives in org.ccg.mpp.cluster rather than unit/cluster because PeerRequest is package-private.
public class PeerRequestTest {
    private final Configuration config = Configuration.getInstance();
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private String origSecret;

    @BeforeEach
    public void setUp() throws Exception {
        origSecret = config.getClusterSecret();
        TestUtils.setPrivateField(config, "clusterSecret", "shared-secret");
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterSecret", origSecret);
        TestUtils.setPrivateField(membershipService, "self", null);
    }

    @Test
    public void test_every_outbound_request_carries_the_secret_and_this_node() throws Exception {
        final var self = ClusterTestHarness.node("self", 9990);
        TestUtils.setPrivateField(membershipService, "self", self);

        final var message = PeerRequest.message(ClusterMessageType.GOSSIP);

        assertEquals(ClusterMessageType.GOSSIP, message.getType());
        assertEquals("shared-secret", message.getSecret());
        assertEquals("self", message.getSender().getNodeId());
    }

    @Test
    public void test_a_request_built_before_joining_carries_no_sender() throws Exception {
        TestUtils.setPrivateField(membershipService, "self", null);

        final var message = PeerRequest.message(ClusterMessageType.GOSSIP);

        assertEquals(ClusterMessageType.GOSSIP, message.getType());
        assertNull(message.getSender());
    }
}

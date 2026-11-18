package org.ccg.mpp.cluster;

import org.ccg.mpp.cluster.msg.ClusterMessage;
import org.ccg.mpp.cluster.msg.ClusterMessageType;
import org.ccg.mpp.ioc.IocContainer;

final class PeerRequest {
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final org.ccg.mpp.cluster.membership.MembershipService membershipService = IocContainer
            .get(org.ccg.mpp.cluster.membership.MembershipService.class);

    private PeerRequest() {
    }

    static ClusterMessage message(ClusterMessageType type) {
        return new ClusterMessage(null, type, clusterConfig.secret(), membershipService.getSelf(), null);
    }
}

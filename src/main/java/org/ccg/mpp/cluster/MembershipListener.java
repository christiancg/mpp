package org.ccg.mpp.cluster;

@FunctionalInterface
public interface MembershipListener {
    void onMembershipChanged(MembershipView view);
}

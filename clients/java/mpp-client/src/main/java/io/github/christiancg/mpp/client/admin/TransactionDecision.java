package io.github.christiancg.mpp.client.admin;

public enum TransactionDecision {
    COMMIT("commit"), ABORT("abort");

    private final String wireName;

    TransactionDecision(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}

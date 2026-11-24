package org.ccg.mpp.data;

public enum MiddlewarePhase {
    INGRESS, EGRESS;

    public static MiddlewarePhase parse(String text) {
        if (text == null) {
            return null;
        }
        for (final var phase : values()) {
            if (phase.name().equals(text)) {
                return phase;
            }
        }
        return null;
    }
}

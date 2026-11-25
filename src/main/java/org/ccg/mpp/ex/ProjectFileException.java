package org.ccg.mpp.ex;

public class ProjectFileException extends Exception {
    public enum Reason {
        NOT_FOUND, EXISTS, KIND_MISMATCH, NOT_EMPTY, CASE_COLLISION, VERSION_CONFLICT, TOO_LARGE, QUOTA_EXCEEDED, INVALID_MOVE
    }

    private final Reason reason;

    public ProjectFileException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

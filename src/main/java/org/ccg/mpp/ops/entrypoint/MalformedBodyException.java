package org.ccg.mpp.ops.entrypoint;

public final class MalformedBodyException extends RuntimeException {
    MalformedBodyException(String detail, Throwable cause) {
        super(detail, cause);
    }
}

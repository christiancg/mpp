package org.ccg.mpp.ops.entrypoint;

public final class InvalidRouteException extends Exception {
    InvalidRouteException(String detail) {
        super(detail);
    }
}

package org.ccg.mpp.ops.middleware;

public class InvalidMiddlewareException extends Exception {
    public InvalidMiddlewareException(String reason) {
        super(reason);
    }
}

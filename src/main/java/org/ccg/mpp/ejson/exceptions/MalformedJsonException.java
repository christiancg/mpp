package org.ccg.mpp.ejson.exceptions;

public class MalformedJsonException extends RuntimeException {
    public MalformedJsonException(String error) {
        super(error);
    }
}

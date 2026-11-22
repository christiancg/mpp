package org.ccg.mpp.http;

public final class MalformedEncodingException extends IllegalArgumentException {
    public MalformedEncodingException(String detail) {
        super(detail);
    }
}

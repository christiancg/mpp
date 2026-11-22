package org.ccg.mpp.http;

import java.io.IOException;

public final class HttpRequest {
    private static final byte[] EMPTY_BODY = new byte[0];

    @FunctionalInterface
    public interface BodySource {
        byte[] read() throws IOException;
    }

    private final HttpRequestHead head;
    private final String remoteAddress;
    private final long contentLength;
    private final BodySource source;
    private byte[] body;

    public HttpRequest(HttpRequestHead head, String remoteAddress, long contentLength, BodySource source) {
        this.head = head;
        this.remoteAddress = remoteAddress;
        this.contentLength = contentLength;
        this.source = source;
    }

    public HttpRequestHead head() {
        return head;
    }

    public String remoteAddress() {
        return remoteAddress;
    }

    public byte[] body() throws IOException {
        if (body == null) {
            body = contentLength == 0 ? EMPTY_BODY : source.read();
        }
        return body;
    }

    public boolean leavesTheStreamAligned() {
        return contentLength == 0 || body != null;
    }
}

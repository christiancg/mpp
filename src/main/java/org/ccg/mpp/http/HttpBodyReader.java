package org.ccg.mpp.http;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public final class HttpBodyReader {
    private static final int MAX_CONTENT_LENGTH_DIGITS = 19;
    private static final long LARGEST_READABLE_BODY = Integer.MAX_VALUE - 8L;

    public static final class LengthRequired extends IOException {
        LengthRequired() {
            super("A request body must be sent with a Content-Length, not a Transfer-Encoding");
        }
    }

    public static final class TooLarge extends IOException {
        TooLarge(long maxBytes) {
            super("The request body is longer than " + maxBytes + " bytes");
        }
    }

    private HttpBodyReader() {
    }

    public static long declaredLength(HttpRequestHead head, long maxBytes) throws IOException {
        if (head.header("Transfer-Encoding") != null) {
            throw new LengthRequired();
        }
        final var declared = head.header("Content-Length");
        if (declared == null) {
            return 0L;
        }
        if (!isDigits(declared)) {
            throw new HttpRequestHead.Malformed("Not a Content-Length: " + declared);
        }
        final var length = Long.parseLong(declared);
        if (length > Math.min(maxBytes, LARGEST_READABLE_BODY)) {
            throw new TooLarge(maxBytes);
        }
        return length;
    }

    public static byte[] read(InputStream in, long length) throws IOException {
        final var body = in.readNBytes((int) length);
        if (body.length < length) {
            throw new EOFException("The connection ended before the request body did");
        }
        return body;
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty() || value.length() > MAX_CONTENT_LENGTH_DIGITS) {
            return false;
        }
        for (var i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}

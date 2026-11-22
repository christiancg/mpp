package org.ccg.mpp.http;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class RawHttpClient implements AutoCloseable {
    private static final int SOCKET_TIMEOUT_MS = 5000;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    record Response(int status, Map<String, String> headers, String body) {
        String connection() {
            return headers.get("connection");
        }
    }

    RawHttpClient(int port) throws IOException {
        this(new Socket("127.0.0.1", port));
    }

    RawHttpClient(Socket socket) throws IOException {
        this.socket = socket;
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    void send(String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    Response readResponse() throws IOException {
        final var statusLine = readLine();
        final var status = Integer.parseInt(statusLine.split(" ", 3)[1]);
        final var headers = new HashMap<String, String>();
        for (var line = readLine(); !line.isEmpty(); line = readLine()) {
            final var colon = line.indexOf(':');
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        final var length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        final var body = in.readNBytes(length);
        return new Response(status, headers, new String(body, StandardCharsets.UTF_8));
    }

    boolean atEndOfStream() {
        try {
            return in.read() < 0;
        } catch (SocketTimeoutException stillOpen) {
            return false;
        } catch (IOException reset) {
            return true;
        }
    }

    private String readLine() throws IOException {
        final var line = new ByteArrayOutputStream();
        for (var next = in.read(); next != '\n'; next = in.read()) {
            if (next < 0) {
                throw new IOException("The connection closed mid-response");
            }
            if (next != '\r') {
                line.write(next);
            }
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}

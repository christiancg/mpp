package org.ccg.mpp.conn;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import javax.net.ssl.SSLSocket;
import org.ccg.mpp.config.Globals;

public final class SocketClosing {
    private SocketClosing() {
    }

    public static void boundTlsClose(Socket socket) throws SocketException {
        if (socket instanceof SSLSocket) {
            socket.setSoLinger(true, Globals.TLS_CLOSE_LINGER_SECONDS);
        }
    }

    public static void closeInBackground(Closeable socket) {
        Thread.ofVirtual().name("mpp-socket-close").start(() -> closeQuietly(socket));
    }

    public static void closeQuietly(Closeable socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}

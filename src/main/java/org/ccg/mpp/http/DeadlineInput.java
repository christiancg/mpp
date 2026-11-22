package org.ccg.mpp.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

public final class DeadlineInput extends FilterInputStream {
    private final Socket socket;
    private volatile long deadlineNanos;

    public DeadlineInput(Socket socket, long deadlineNanos) throws IOException {
        super(socket.getInputStream());
        this.socket = socket;
        this.deadlineNanos = deadlineNanos;
    }

    public void rearm(long timeoutMillis) {
        deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    @Override
    public int read() throws IOException {
        armTimeout();
        return super.read();
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public int read(byte[] buffer, int offset, int length) throws IOException {
        armTimeout();
        return super.read(buffer, offset, length);
    }

    private void armTimeout() throws IOException {
        final var remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remainingMs <= 0) {
            throw new SocketTimeoutException("The request did not arrive in time");
        }
        socket.setSoTimeout((int) Math.min(remainingMs, Integer.MAX_VALUE));
    }
}

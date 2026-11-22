package org.ccg.mpp.http;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.log.Logger;

public class HttpServer {
    private static final int BACKLOG = 128;
    private static final int LINGER_MS = 1000;
    private static final int LINGER_MAX_BYTES = 64 * 1024;
    private static final int LINGER_BUFFER_BYTES = 8192;
    private static final String[] HTTP_1_1_ONLY = {"http/1.1"};
    private final Logger logger = Logger.logFor(HttpServer.class);
    private final Set<Socket> idleSockets = ConcurrentHashMap.newKeySet();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ReentrantLock drainLock = new ReentrantLock();
    private final Condition drained = drainLock.newCondition();
    private volatile ServerSocket serverSocket;
    private volatile boolean stopping;
    private volatile boolean tls;
    private volatile HttpLimits limits;
    private volatile HttpHandler handler;
    private volatile Semaphore connections;

    public synchronized void start(int port, String bindAddress, SSLServerSocketFactory tlsFactory,
            List<String> tlsProtocols, HttpLimits limits, HttpHandler handler) throws IOException {
        if (serverSocket != null) {
            throw new IllegalStateException("The HTTP entrypoint listener is already listening");
        }
        final ServerSocket listening;
        try {
            listening = open(port, bindAddress, tlsFactory, tlsProtocols);
        } catch (IOException e) {
            throw new IOException("could not bind the HTTP entrypoint listener to port " + port + " (httpPort): "
                    + e.getMessage() + "; set httpPort to a free port, or httpPort=0 to turn the listener off", e);
        }
        this.limits = limits;
        this.handler = handler;
        this.connections = limits.maxConnections() > 0 ? new Semaphore(limits.maxConnections()) : null;
        this.tls = tlsFactory != null;
        this.stopping = false;
        serverSocket = listening;
        Thread.ofPlatform().daemon().name("mpp-http-accept").start(() -> acceptUntilClosed(listening));
        logger.info(
                "HTTP entrypoints are listening on port " + listening.getLocalPort() + (tls ? " (https)" : " (http)"));
    }

    private static ServerSocket open(int port, String bindAddress, SSLServerSocketFactory tlsFactory,
            List<String> tlsProtocols) throws IOException {
        final var address = bindAddress == null || bindAddress.isBlank() ? null : InetAddress.getByName(bindAddress);
        if (tlsFactory == null) {
            return new ServerSocket(port, BACKLOG, address);
        }
        final var secured = (SSLServerSocket) tlsFactory.createServerSocket();
        try {
            secured.setEnabledProtocols(tlsProtocols.toArray(String[]::new));
            final var parameters = secured.getSSLParameters();
            parameters.setApplicationProtocols(HTTP_1_1_ONLY);
            parameters.setNeedClientAuth(false);
            secured.setSSLParameters(parameters);
            secured.bind(new InetSocketAddress(address, port), BACKLOG);
            return secured;
        } catch (IOException | RuntimeException e) {
            secured.close();
            throw e;
        }
    }

    public int boundPort() {
        final var listening = serverSocket;
        return listening == null ? -1 : listening.getLocalPort();
    }

    public boolean isTls() {
        return tls;
    }

    public synchronized void stopAccepting() {
        stopping = true;
        final var listening = serverSocket;
        serverSocket = null;
        if (listening != null) {
            closeQuietly(listening);
        }
        for (final var idle : idleSockets) {
            closeQuietly(idle);
        }
    }

    public boolean drain(long millis) {
        var remaining = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, millis));
        drainLock.lock();
        try {
            while (inFlight.get() > 0) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = drained.awaitNanos(remaining);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            drainLock.unlock();
        }
    }

    private void acceptUntilClosed(ServerSocket listening) {
        while (!listening.isClosed()) {
            try {
                final var socket = listening.accept();
                Thread.ofVirtual().name("mpp-http-connection").start(() -> serve(socket));
            } catch (IOException e) {
                if (!listening.isClosed()) {
                    logger.warning("Could not accept an HTTP entrypoint connection: " + e.getMessage());
                }
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            final var slots = connections;
            if (slots != null && !slots.tryAcquire()) {
                socket.setSoTimeout((int) limits.readTimeoutMs());
                refuse(socket, HttpResponse.error(503, "Too many connections"));
                return;
            }
            try {
                converse(socket);
                lingerUntilTheClientCloses(socket);
            } finally {
                if (slots != null) {
                    slots.release();
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void converse(Socket socket) throws IOException {
        final var deadline = new DeadlineInput(socket, System.nanoTime());
        final var in = new BufferedInputStream(deadline);
        final var out = new BufferedOutputStream(socket.getOutputStream());
        final var remoteAddress = socket.getInetAddress().getHostAddress();
        var waitMillis = limits.readTimeoutMs();
        while (!stopping) {
            deadline.rearm(waitMillis);
            if (!awaitFirstByte(socket, in)) {
                return;
            }
            deadline.rearm(limits.readTimeoutMs());
            final var head = readHead(in, out);
            if (head == null) {
                return;
            }
            if (!serveOne(head, in, out, remoteAddress, deadline)) {
                return;
            }
            waitMillis = limits.idleTimeoutMs();
        }
    }

    private boolean awaitFirstByte(Socket socket, BufferedInputStream in) throws IOException {
        idleSockets.add(socket);
        try {
            in.mark(1);
            if (in.read() < 0) {
                return false;
            }
            in.reset();
            return !stopping;
        } catch (SocketTimeoutException timedOut) {
            return false;
        } finally {
            idleSockets.remove(socket);
        }
    }

    private HttpRequestHead readHead(BufferedInputStream in, OutputStream out) throws IOException {
        try {
            return HttpRequestHead.read(in, Globals.HTTP_MAX_HEAD_BYTES);
        } catch (HttpRequestHead.TooLarge e) {
            HttpResponseWriter.write(out, HttpResponse.error(431, "Request header fields too large"), false);
        } catch (HttpRequestHead.Malformed e) {
            HttpResponseWriter.write(out, HttpResponse.error(400, "Bad request"), false);
        } catch (SocketTimeoutException e) {
            HttpResponseWriter.write(out, HttpResponse.error(408, "The request head did not arrive in time"), false);
        } catch (EOFException e) {
            return null;
        }
        return null;
    }

    private boolean serveOne(HttpRequestHead head, BufferedInputStream in, OutputStream out, String remoteAddress,
            DeadlineInput deadline) throws IOException {
        final long length;
        try {
            length = HttpBodyReader.declaredLength(head, limits.maxRequestBytes());
        } catch (HttpBodyReader.LengthRequired e) {
            HttpResponseWriter.write(out, HttpResponse.error(411, "Chunked request bodies are not supported"), false);
            return false;
        } catch (HttpBodyReader.TooLarge e) {
            HttpResponseWriter.write(out, HttpResponse.error(413, "The request body is too large"), false);
            return false;
        } catch (HttpRequestHead.Malformed e) {
            HttpResponseWriter.write(out, HttpResponse.error(400, "Bad request"), false);
            return false;
        }
        final var request = new HttpRequest(head, remoteAddress, length, () -> {
            deadline.rearm(limits.readTimeoutMs());
            return HttpBodyReader.read(in, length);
        });
        inFlight.incrementAndGet();
        try {
            final var response = stopping ? HttpResponse.error(503, "The server is shutting down") : answer(request);
            final var keepAlive = !response.closes() && !stopping && request.leavesTheStreamAligned()
                    && !head.wantsClose();
            HttpResponseWriter.write(out, response, keepAlive);
            return keepAlive;
        } finally {
            finishedOne();
        }
    }

    private HttpResponse answer(HttpRequest request) {
        try {
            return handler.handle(request);
        } catch (RuntimeException e) {
            logger.error("An HTTP entrypoint request failed unexpectedly", e);
            return HttpResponse.error(500, "Internal Server Error").closing();
        }
    }

    private void finishedOne() {
        if (inFlight.decrementAndGet() == 0) {
            drainLock.lock();
            try {
                drained.signalAll();
            } finally {
                drainLock.unlock();
            }
        }
    }

    private static void refuse(Socket socket, HttpResponse response) throws IOException {
        HttpResponseWriter.write(socket.getOutputStream(), response, false);
        lingerUntilTheClientCloses(socket);
    }

    private static void lingerUntilTheClientCloses(Socket socket) throws IOException {
        if (socket instanceof SSLSocket) {
            return;
        }
        socket.shutdownOutput();
        socket.setSoTimeout(LINGER_MS);
        final var in = socket.getInputStream();
        final var discard = new byte[LINGER_BUFFER_BYTES];
        var left = LINGER_MAX_BYTES;
        for (var read = in.read(discard); read > 0 && left > 0; read = in.read(discard)) {
            left -= read;
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}

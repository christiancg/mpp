package io.github.christiancg.mpp.client.files;

import io.github.christiancg.mpp.client.error.FileChangedException;
import io.github.christiancg.mpp.client.internal.ClientContext;
import io.github.christiancg.mpp.client.internal.FileReplies;
import io.github.christiancg.mpp.client.internal.FileRequestEncoder;
import io.github.christiancg.mpp.client.protocol.Operation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;

public final class ProjectFiles {
    public static final int UPLOAD_CHUNK_BYTES = 4 * 1024 * 1024;
    public static final int READ_WINDOW_BYTES = 4 * 1024 * 1024;
    public static final int MAX_READ_RESTARTS = 3;
    private static final String ALPHANUMERICS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int UPLOAD_SUFFIX_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ClientContext context;
    private final String project;

    public ProjectFiles(ClientContext context, String project) {
        this.context = context;
        this.project = project;
    }

    public FileWritten write(String path, byte[] content) {
        return write(path, content, WriteOptions.defaults());
    }

    public FileWritten write(String path, byte[] content, WriteOptions options) {
        Objects.requireNonNull(content, "content");
        final var reply = send(Operation.WRITE_FILE, FileRequestEncoder.write(project, path, content, options));
        return FileReplies.written(reply, path);
    }

    public FileWritten writeText(String path, String text) {
        return writeText(path, text, WriteOptions.defaults());
    }

    public FileWritten writeText(String path, String text, WriteOptions options) {
        Objects.requireNonNull(text, "text");
        final var reply = send(Operation.WRITE_FILE, FileRequestEncoder.writeText(project, path, text, options));
        return FileReplies.written(reply, path);
    }

    public FileWritten append(String path, byte[] content) {
        return write(path, content, WriteOptions.builder().mode(WriteMode.APPEND).build());
    }

    public FileWritten upload(String path, InputStream content) throws IOException {
        final var staging = stagingPathOf(path);
        try {
            var chunk = content.readNBytes(UPLOAD_CHUNK_BYTES);
            write(staging, chunk, WriteOptions.builder().mode(WriteMode.CREATE).build());
            long size = chunk.length;
            while ((chunk = content.readNBytes(UPLOAD_CHUNK_BYTES)).length > 0) {
                append(staging, chunk);
                size += chunk.length;
            }
            final var moved = move(staging, path, true);
            return new FileWritten(moved.path(), moved.version(), size);
        } catch (IOException | RuntimeException e) {
            discardStaging(staging, e);
            throw e;
        }
    }

    public byte[] read(String path) {
        for (var attempt = 0; attempt <= MAX_READ_RESTARTS; attempt++) {
            final var buffer = new ByteArrayOutputStream();
            if (readWindows(path, buffer::writeBytes)) {
                return buffer.toByteArray();
            }
        }
        throw new FileChangedException(path, MAX_READ_RESTARTS + 1);
    }

    public String readText(String path) {
        return new String(read(path), StandardCharsets.UTF_8);
    }

    public FileRange readRange(String path, long offset, int length) {
        return range(path, offset, length, FileRequestEncoder.BASE64);
    }

    public FileRange readTextRange(String path, long offset, int length) {
        return range(path, offset, length, FileRequestEncoder.UTF8);
    }

    public long download(String path, OutputStream target) throws IOException {
        final var written = new long[1];
        final boolean complete = readWindows(path, window -> {
            target.write(window);
            written[0] += window.length;
        });
        if (!complete) {
            throw new FileChangedException(path, 1);
        }
        return written[0];
    }

    public FileInfo stat(String path) {
        return FileReplies.info(send(Operation.STAT_FILE, FileRequestEncoder.stat(project, path)), path);
    }

    public Iterable<FileInfo> list(String path) {
        return list(path, ListOptions.defaults());
    }

    public Iterable<FileInfo> list(String path, ListOptions options) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return () -> new Pages(path, options);
    }

    public long delete(String path) {
        return delete(path, false);
    }

    public long delete(String path, boolean recursive) {
        final var reply = send(Operation.DELETE_FILE, FileRequestEncoder.delete(project, path, recursive));
        return reply.path("deleted").asLong(0);
    }

    public FileMoved move(String from, String to) {
        return move(from, to, false);
    }

    public FileMoved move(String from, String to, boolean overwrite) {
        final var reply = send(Operation.MOVE_FILE, FileRequestEncoder.move(project, from, to, overwrite));
        return FileReplies.moved(reply, to);
    }

    public DirectoryCreated makeDirectory(String path) {
        final var reply = send(Operation.MAKE_DIRECTORY, FileRequestEncoder.makeDirectory(project, path));
        return FileReplies.directory(reply, path);
    }

    private FileRange range(String path, long offset, int length, String encoding) {
        if (offset < 0 || length < 1) {
            throw new IllegalArgumentException("offset must be >= 0 and length >= 1: " + offset + ", " + length);
        }
        final var reply = send(Operation.READ_FILE, FileRequestEncoder.read(project, path, offset, length, encoding));
        return FileReplies.range(reply, path);
    }

    private <E extends Exception> boolean readWindows(String path, WindowSink<E> sink) throws E {
        long offset = 0;
        String version = null;
        while (true) {
            final var reply = send(Operation.READ_FILE,
                    FileRequestEncoder.read(project, path, offset, READ_WINDOW_BYTES, FileRequestEncoder.BASE64));
            final var windowVersion = FileReplies.versionOf(reply);
            if (version != null && !version.equals(windowVersion)) {
                return false;
            }
            version = windowVersion;
            final var window = FileRequestEncoder.contentOf(reply);
            sink.accept(window);
            offset += window.length;
            if (window.length == 0 || reply.path("eof").asBoolean(true)) {
                return true;
            }
        }
    }

    private void discardStaging(String staging, Exception failure) {
        try {
            delete(staging);
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private ObjectNode send(Operation operation, ObjectNode request) {
        return context.channel().send(operation, request);
    }

    static String stagingPathOf(String path) {
        Objects.requireNonNull(path, "path");
        var trimmed = path;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        final var slash = trimmed.lastIndexOf('/');
        final var name = trimmed.substring(slash + 1);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("an upload needs a file path, not " + path);
        }
        final var suffix = new StringBuilder(UPLOAD_SUFFIX_LENGTH);
        for (var i = 0; i < UPLOAD_SUFFIX_LENGTH; i++) {
            suffix.append(ALPHANUMERICS.charAt(RANDOM.nextInt(ALPHANUMERICS.length())));
        }
        return trimmed.substring(0, slash + 1) + "." + name + ".upload-" + suffix;
    }

    @FunctionalInterface
    private interface WindowSink<E extends Exception> {
        void accept(byte[] window) throws E;
    }

    private final class Pages implements Iterator<FileInfo> {
        private final String path;
        private final ListOptions options;
        private Iterator<FileInfo> page;
        private String after;
        private boolean exhausted;

        private Pages(String path, ListOptions options) {
            this.path = path;
            this.options = options;
            this.after = options.startAfter().orElse(null);
        }

        @Override
        public boolean hasNext() {
            while ((page == null || !page.hasNext()) && !exhausted) {
                final var reply = send(Operation.LIST_FILES, FileRequestEncoder.list(project, path, options, after));
                page = FileReplies.entries(reply).iterator();
                final var next = FileReplies.next(reply).orElse(null);
                exhausted = next == null || next.equals(after);
                after = next;
            }
            return page != null && page.hasNext();
        }

        @Override
        public FileInfo next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return page.next();
        }
    }
}

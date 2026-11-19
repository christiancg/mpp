package org.ccg.mpp.fs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.ccg.mpp.data.PkIndexEntry;

final class StorageFixture {
    static final long OLD_VERSION = 1_700_000_000_000L;
    static final long NEW_VERSION = 1_800_000_000_000L;

    final String projectName;
    final String collName;
    final FilePaths paths;
    final PkIndexStore store;
    final WriteIntentJournal journal;
    final WriteIntentRecovery recovery;

    StorageFixture(File root, String projectName, String collName) throws IOException {
        this.projectName = projectName;
        this.collName = collName;
        paths = new FilePaths();
        paths.useDbPath(root.getAbsolutePath());
        Files.createDirectories(paths.collectionFolder(projectName, collName).toPath());
        store = new PkIndexStore(paths);
        journal = new WriteIntentJournal(paths);
        recovery = new WriteIntentRecovery(paths, store, journal);
    }

    static String record(String id, String value) {
        return "{\"_id\":\"" + id + "\",\"v\":\"" + value + "\"}\n";
    }

    File page(long page) {
        return paths.collectionPage(projectName, collName, page);
    }

    void seed(long page, String... ids) throws IOException {
        for (final var id : ids) {
            final var position = page(page).exists() ? page(page).length() : 0L;
            final var text = record(id, "old-" + id);
            Files.writeString(page(page).toPath(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            PageLengths.forget(page(page));
            store.appendRows(projectName, collName, List.of(new PkIndexEntry(projectName, collName, id, position,
                    text.getBytes(StandardCharsets.UTF_8).length, page, OLD_VERSION)));
        }
    }

    PkIndexEntry indexed(String id) throws IOException {
        return store.readWholePkIndexFile(projectName, collName).stream().filter(entry -> entry.getValue().equals(id))
                .findFirst().orElse(null);
    }

    List<PkIndexEntry> indexedRows() throws IOException {
        return store.readWholePkIndexFile(projectName, collName);
    }

    String readAt(PkIndexEntry entry) throws IOException {
        final var bytes = Files.readAllBytes(page(entry.getPage()).toPath());
        return new String(bytes, (int) entry.getPosition(), (int) entry.getLength(), StandardCharsets.UTF_8);
    }

    String firstPageText() throws IOException {
        return Files.readString(page(0).toPath(), StandardCharsets.UTF_8);
    }

    boolean everyRowReadsItsOwnRecord() throws IOException {
        for (final var row : indexedRows()) {
            if (!readAt(row).startsWith("{\"_id\":\"" + row.getValue() + "\"")) {
                return false;
            }
        }
        return true;
    }

    int openIntents() throws IOException {
        return journal.openIntents(projectName, collName).size();
    }
}

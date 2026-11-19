package org.ccg.mpp.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongUnaryOperator;
import java.util.stream.Stream;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.FieldIndexEntry;
import org.ccg.mpp.data.IndexKind;
import org.ccg.mpp.data.IndexedDbEntry;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.data.admin.AdminPageEntry;
import org.ccg.mpp.ex.DirectoryNotFoundException;

public class FileSystem {
    private final Configuration configuration = Configuration.getInstance();
    private final FilePaths paths = new FilePaths();
    private final IndexMarkers indexMarkers = new IndexMarkers(paths);
    private final MetadataFiles metadataFiles = new MetadataFiles(paths);
    private final IndexFiles indexFiles = new IndexFiles(paths);
    private final FolderQuarantine folderQuarantine = new FolderQuarantine(paths);
    private final FieldIndexStore fieldIndexStore = new FieldIndexStore(paths);
    private final FieldIndexLoader fieldIndexLoader = new FieldIndexLoader(paths);
    private final DocumentPageStore documentPageStore = new DocumentPageStore(paths);
    private final PkIndexStore pkIndexStore = new PkIndexStore(paths);
    private final SnapshotFiles snapshotFiles = new SnapshotFiles(paths, pkIndexStore);
    private final WriteIntentJournal writeIntentJournal = new WriteIntentJournal(paths);
    private final PageVacuum pageVacuum = new PageVacuum(paths, pkIndexStore, writeIntentJournal);
    private final RecordReplacer recordReplacer = new RecordReplacer(paths, pkIndexStore, pageVacuum);
    private final PageAppender pageAppender = new PageAppender(paths, pkIndexStore, pageVacuum);
    private final WriteIntentRecovery writeIntentRecovery = new WriteIntentRecovery(paths, pkIndexStore,
            writeIntentJournal);
    private final NodeIdStore nodeIdStore = new NodeIdStore(paths);
    private final FormatStampFile formatStamp = new FormatStampFile(paths);
    private final WriteAheadLog writeAheadLog = new WriteAheadLog(paths);
    private final TombstoneStore tombstones = new TombstoneStore(paths, writeAheadLog);
    private final WalCheckpoints walCheckpoints = new WalCheckpoints(paths, writeAheadLog);

    public void onVacuum(Consumer<VacuumedPage> listener) {
        pageVacuum.onVacuum(listener);
    }

    public void guardCheckpoints(CheckpointGuard guard) {
        WriteAheadLog.guardWith(guard);
    }

    public void createBaseDbPath() {
        paths.useDbPath(configuration.getFilePath());
        final var directory = new File(paths.dbPath());
        FileTreeDeleter.forgetWrittenLengthsUnder(directory);
        if (!directory.exists()) {
            try {
                OwnerOnly.createDirectories(directory.toPath());
            } catch (IOException e) {
                throw new DirectoryNotFoundException(directory.getAbsolutePath());
            }
        }
    }

    public void verifyDeviceSync() throws IOException {
        SyncedWrites.verifyFolderSync(new File(paths.dbPath()).toPath());
    }

    public void installDurableShadow(Path shadowRoot) throws IOException {
        SyncedWrites.useForcer(DurableShadow.install(Path.of(paths.dbPath()), shadowRoot));
    }

    public void createAdminProject() throws IOException {
        createProjectFolder(Globals.ADMIN_PROJECT_NAME);
        createAdminPagesFolder();
        for (final var collName : Globals.ADMIN_COLLECTION_NAMES) {
            createCollectionFile(Globals.ADMIN_PROJECT_NAME, collName);
        }
        for (final var collName : Globals.ADMIN_COLLECTION_NAMES) {
            createCollectionFile(Globals.ADMIN_PAGES_PROJECT_NAME,
                    AdminPageEntry.pagesCollectionName(Globals.ADMIN_PROJECT_NAME, collName));
        }
    }

    private void createAdminPagesFolder() {
        final var pagesFolder = paths.adminPagesFolder();
        if (pagesFolder.exists()) {
            return;
        }
        try {
            OwnerOnly.createDirectories(pagesFolder.toPath());
        } catch (IOException e) {
            throw new DirectoryNotFoundException(pagesFolder.getAbsolutePath());
        }
    }

    public boolean createProjectFolder(String projectName) {
        final var projectFolder = paths.rawProjectFolder(projectName);
        if (!projectFolder.exists()) {
            FileTreeDeleter.forgetWrittenLengthsUnder(projectFolder);
            return OwnerOnly.createDirectory(projectFolder.toPath());
        }
        return true;
    }

    public boolean deleteProject(String projectName) {
        final var projectFolder = paths.rawProjectFolder(projectName);
        final var deleted = isAbsent(projectFolder) || FileTreeDeleter.delete(projectFolder);
        FileLocks.evictAllUnder(projectFolder);
        paths.forgetProject(projectName);
        return deleted;
    }

    public boolean createCollectionFile(String projectName, String collectionName) throws IOException {
        final var collectionFile = paths.collectionPage(projectName, collectionName, 0);
        final var collectionFolder = new File(collectionFile.getParent());
        if (!collectionFolder.exists()) {
            FileTreeDeleter.forgetWrittenLengthsUnder(collectionFolder);
            if (OwnerOnly.createDirectory(collectionFolder.toPath())) {
                return OwnerOnly.createNewOwnerOnlyFile(collectionFile.toPath());
            } else {
                return false;
            }
        } else {
            if (!collectionFile.exists()) {
                PageLengths.forget(collectionFile);
                return OwnerOnly.createNewOwnerOnlyFile(collectionFile.toPath());
            }
            return true;
        }
    }

    public boolean deleteCollectionFiles(String projectName, String collectionName) {
        final var collectionFile = paths.collectionPage(projectName, collectionName, 0);
        final var collectionFolder = new File(collectionFile.getParent());
        final var deleted = isAbsent(collectionFolder) || FileTreeDeleter.delete(collectionFolder);
        FileLocks.evictAllUnder(collectionFolder);
        paths.forgetCollection(projectName, collectionName);
        return deleted;
    }

    private static boolean isAbsent(File folder) {
        return !Files.exists(folder.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    public SnapshotFiles snapshotFiles() {
        return snapshotFiles;
    }

    public MetadataFiles metadataFiles() {
        return metadataFiles;
    }

    public IndexFiles indexFiles() {
        return indexFiles;
    }

    public IndexMarkers indexMarkers() {
        return indexMarkers;
    }

    public FolderQuarantine folderQuarantine() {
        return folderQuarantine;
    }

    public NodeIdStore nodeIds() {
        return nodeIdStore;
    }

    public FormatStampFile formatStamp() {
        return formatStamp;
    }

    public TombstoneStore tombstones() {
        return tombstones;
    }

    public List<PkIndexEntry> readWholePkIndexFile(String projectName, String collectionName) throws IOException {
        return pkIndexStore.readWholePkIndexFile(projectName, collectionName);
    }

    public Map<String, PkIndexEntry> pkRowsFor(String projectName, String collName, Collection<String> ids)
            throws IOException {
        return pkIndexStore.rowsFor(projectName, collName, ids);
    }

    public OptionalLong trustedLiveRows(String projectName, String collName) {
        return pkIndexStore.trustedLiveRows(projectName, collName);
    }

    public long pkIndexFileBytes(String projectName, String collectionName) {
        return paths.pkIndexFile(projectName, collectionName).length();
    }

    public long indexFileBytes(String projectName, String collName, String fieldName, String indexTypeLabel) {
        final var indexFile = paths.indexFile(projectName, collName, fieldName, indexTypeLabel);
        return indexFile == null ? 0L : indexFile.length() + FilePaths.logFileOf(indexFile).length();
    }

    public DbEntry getById(PkIndexEntry pkIndexEntry) throws Exception {
        return documentPageStore.getById(pkIndexEntry);
    }

    public List<DbEntry> getByIndexEntries(List<PkIndexEntry> entries) throws IOException {
        return documentPageStore.getByIndexEntries(entries);
    }

    public <T extends DbEntry> List<IndexedDbEntry> bulkInsertIntoCollection(final String projectName,
            final String collName, final List<T> entries) throws IOException {
        return bulkInsertIntoCollection(projectName, collName, entries, DbEntry::getPage);
    }

    public <T extends DbEntry> List<IndexedDbEntry> bulkInsertIntoCollection(final String projectName,
            final String collName, final List<T> entries, final Function<? super T, Long> storagePageResolver)
            throws IOException {
        return pageAppender.bulkInsert(projectName, collName, entries, storagePageResolver, DbEntry::encodeLine);
    }

    public <T extends DbEntry> List<IndexedDbEntry> bulkInsertIntoCollection(final String projectName,
            final String collName, final List<T> entries, final Function<? super T, Long> storagePageResolver,
            final Function<? super T, byte[]> encodedLines) throws IOException {
        return pageAppender.bulkInsert(projectName, collName, entries, storagePageResolver, encodedLines);
    }

    public <T extends DbEntry> List<IndexedDbEntry> writeBatch(String projectName, String collName, List<T> inserts,
            Function<? super T, Long> pageOf, Function<? super T, byte[]> lines, List<PkIndexEntry> deletes)
            throws IOException {
        return pageAppender.bulkInsertAndDelete(projectName, collName, inserts, pageOf, lines, deletes);
    }

    public PkIndexEntry insertIntoCollection(DbEntry entry) throws IOException {
        return pageAppender.insert(entry, entry.encodeLine());
    }

    public PkIndexEntry insertIntoCollection(DbEntry entry, byte[] encodedLine) throws IOException {
        return pageAppender.insert(entry, encodedLine);
    }

    public void deleteFromCollection(PkIndexEntry pkIndexEntry) throws IOException {
        recordReplacer.delete(pkIndexEntry);
    }

    public void deleteFromCollection(PkIndexEntry pkIndexEntry, List<PkIndexEntry> residentRows,
            LongFunction<PageOccupancy> occupancy) throws IOException {
        recordReplacer.delete(pkIndexEntry, residentRows, occupancy);
    }

    public void vacuumPagesAfterDelete(String projectName, String collName, Collection<Long> pages,
            List<PkIndexEntry> residentRows, LongFunction<PageOccupancy> occupancy) {
        recordReplacer.vacuumAfterDeletes(projectName, collName, pages, residentRows, occupancy);
    }

    public PkIndexEntry relocate(DbEntry entry, PkIndexEntry oldRow, long targetPage, byte[] encodedLine,
            List<PkIndexEntry> residentRows, LongFunction<PageOccupancy> occupancy) throws IOException {
        entry.setPage(targetPage);
        return recordReplacer.replace(entry, encodedLine, oldRow, targetPage, null, residentRows, occupancy);
    }

    public List<PkIndexEntry> redoWriteAheadLogs() throws IOException {
        return WalRedo.redoAll(paths);
    }

    public void recoverWriteIntents() throws IOException {
        writeIntentRecovery.recoverAll();
        cutUncommittedAdminCopies();
    }

    private void cutUncommittedAdminCopies() throws IOException {
        for (final var collName : Globals.ADMIN_COLLECTION_NAMES) {
            cutUncommittedCopiesOf(Globals.ADMIN_PROJECT_NAME, collName);
            cutUncommittedCopiesOf(Globals.ADMIN_PAGES_PROJECT_NAME,
                    AdminPageEntry.pagesCollectionName(Globals.ADMIN_PROJECT_NAME, collName));
        }
    }

    private void cutUncommittedCopiesOf(String projectName, String collName) throws IOException {
        if (paths.collectionFolder(projectName, collName).isDirectory()) {
            OrphanedPageRecords.cutUncommittedCopies(paths, pkIndexStore, projectName, collName,
                    pageFileLengths(projectName, collName).keySet());
        }
    }

    public List<IndexedDbEntry> bulkUpdateFromCollection(String projectName, String collName,
            List<IndexedDbEntry> entries) throws IOException {
        return bulkUpdateFromCollection(projectName, collName, entries, null, null);
    }

    public List<IndexedDbEntry> bulkUpdateFromCollection(String projectName, String collName,
            List<IndexedDbEntry> entries, List<PkIndexEntry> residentRows,
            Function<? super IndexedDbEntry, byte[]> encodedLines) throws IOException {
        return bulkUpdateFromCollection(projectName, collName, entries, residentRows, encodedLines, null);
    }

    public List<IndexedDbEntry> bulkUpdateFromCollection(String projectName, String collName,
            List<IndexedDbEntry> entries, List<PkIndexEntry> residentRows,
            Function<? super IndexedDbEntry, byte[]> encodedLines, LongFunction<PageOccupancy> occupancy)
            throws IOException {
        return recordReplacer.bulkReplace(projectName, collName, entries, residentRows, encodedLines, occupancy);
    }

    public PkIndexEntry updateFromCollection(DbEntry entry, PkIndexEntry pkIndexEntry) throws IOException {
        return updateFromCollection(entry, pkIndexEntry, entry.encodeLine(), null);
    }

    public PkIndexEntry updateFromCollection(DbEntry entry, PkIndexEntry pkIndexEntry, byte[] encodedLine,
            List<PkIndexEntry> residentRows) throws IOException {
        return updateFromCollection(entry, pkIndexEntry, encodedLine, null, residentRows, null);
    }

    public PkIndexEntry updateFromCollection(DbEntry entry, PkIndexEntry pkIndexEntry, byte[] encodedLine,
            PagePlacement placement, List<PkIndexEntry> residentRows, LongFunction<PageOccupancy> occupancy)
            throws IOException {
        return recordReplacer.replace(entry, encodedLine, pkIndexEntry, pkIndexEntry.getPage(), placement, residentRows,
                occupancy);
    }

    public void writeIndexFile(String projectName, String collName, String fieldName,
            Map<Class<?>, List<FieldIndexEntry<?>>> indexEntryMap) {
        fieldIndexStore.writeIndexFile(projectName, collName, fieldName, indexEntryMap);
    }

    public void writeHashIndexFile(String projectName, String collName, String fieldName, IndexKind kind,
            List<FieldIndexEntry<String>> entries) {
        fieldIndexStore.writeHashIndexFile(projectName, collName, fieldName, kind, entries);
    }

    public boolean indexTypeFileExists(String projectName, String collName, String fieldName, String typeLabel) {
        final var indexFile = paths.indexFile(projectName, collName, fieldName, typeLabel);
        return indexFile != null && (indexFile.exists() || FilePaths.logFileOf(indexFile).exists());
    }

    public PendingFold appendIndexLog(String projectName, String collName, String fieldName, String typeLabel,
            List<IndexLogChange> changes, List<? extends FieldIndexEntry<?>> survivors, boolean refused)
            throws IOException {
        return fieldIndexStore.appendIndexLog(projectName, collName, fieldName, typeLabel, changes, survivors,
                refused ? Globals.REFUSED_INDEX_FOLD_DIVISOR : 1);
    }

    public PendingFold appendIndexLogOnly(String projectName, String collName, String fieldName, String typeLabel,
            List<IndexLogChange> changes, IndexTypeReader foldSource) throws IOException {
        return fieldIndexStore.appendIndexLogOnly(projectName, collName, fieldName, typeLabel, changes, foldSource);
    }

    public PendingFold.Outcome fold(PendingFold fold) throws IOException {
        return fieldIndexStore.fold(fold);
    }

    public long indexTypeStamp(String projectName, String collName, String fieldName, String typeLabel) {
        return fieldIndexStore.stampOf(projectName, collName, fieldName, typeLabel);
    }

    public boolean dropIndex(String projectName, String collName, String fieldName) {
        return fieldIndexStore.dropIndex(projectName, collName, fieldName);
    }

    public <T> List<FieldIndexEntry<T>> readWholeFieldIndexFiles(String projectName, String collName, String fieldName,
            Class<T> indexType) throws IOException {
        return fieldIndexLoader.readWholeFieldIndexFiles(projectName, collName, fieldName, indexType);
    }

    public List<FieldIndexEntry<String>> readWholeHashIndexFile(String projectName, String collName, String fieldName,
            IndexKind kind) throws IOException {
        return fieldIndexLoader.readWholeHashIndexFile(projectName, collName, fieldName, kind);
    }

    public Map<String, DbEntry> readWholeCollectionPage(String projectName, String collectionName, long page)
            throws IOException {
        return documentPageStore.readWholeCollectionPage(projectName, collectionName, page);
    }

    public Stream<Map<String, DbEntry>> streamPages(String projectName, String collName) throws IOException {
        return documentPageStore.streamPages(projectName, collName);
    }

    public Stream<DbEntry> streamEntries(String projectName, String collName) throws IOException {
        return documentPageStore.streamEntries(projectName, collName);
    }

    public Map<Long, Long> pageFileLengths(String projectName, String collName) throws IOException {
        return documentPageStore.pageFileLengths(projectName, collName);
    }

    public long pageFileLength(String projectName, String collName, long page) {
        return PageLengths.lengthOf(paths.collectionPage(projectName, collName, page));
    }

    public LongUnaryOperator pageFileLengthsOf(String projectName, String collName) {
        return PageLengths.of(paths, projectName, collName);
    }

    public void healTornPageTails(String projectName, String collName) throws IOException {
        healTornPageTails(pkRowsSession(projectName, collName));
    }

    public PkRowsSession pkRowsSession(String projectName, String collName) {
        return new PkRowsSession(pkIndexStore, projectName, collName);
    }

    public long highestPkVersion(String projectName, String collName) throws IOException {
        return pkIndexStore.highestVersion(projectName, collName);
    }

    public void healTornPageTails(PkRowsSession session) throws IOException {
        TornPageTail.healAll(paths, session.projectName(), session.collName(),
                pageFileLengths(session.projectName(), session.collName()).keySet(), session::rows);
    }

    public List<PkIndexEntry> retireDanglingPkEntries(PkRowsSession session) throws IOException {
        return OrphanedPageRecords.retireDangling(session, pageFileLengths(session.projectName(), session.collName()));
    }

    public void checkpointWriteAheadLogs(long deadline) {
        walCheckpoints.atShutdown(deadline);
    }

    public int checkpointIdleWriteAheadLogs(long idleMillis, int maxLogs, CollectionTryLock collectionLocks) {
        return walCheckpoints.idle(idleMillis, maxLogs, collectionLocks);
    }

    public void onCheckpointRequest(BooleanSupplier submit) {
        WalCheckpointRequests.listenWith(submit);
    }

    public void checkpointRequestedWriteAheadLogs(CollectionTryLock collectionLocks) {
        walCheckpoints.requested(collectionLocks);
    }

    public void openWriteGroup(String projectName, String collName) throws IOException {
        writeAheadLog.openGroup(projectName, collName);
    }

    public boolean reopenWriteGroupIfEnded(String projectName, String collName) throws IOException {
        if (writeAheadLog.openGroupOf(projectName, collName) != null) {
            return false;
        }
        writeAheadLog.openGroup(projectName, collName);
        return true;
    }

    public boolean forceWriteGroup(String projectName, String collName) throws IOException {
        final var deleteTombstones = tombstones.startGroupForce(projectName, collName);
        return tombstones.forcedBeside(deleteTombstones, writeAheadLog.forceGroup(projectName, collName), projectName,
                collName);
    }

    public boolean forceGroupTombstones(String projectName, String collName) {
        return tombstones.forcedBeside(tombstones.startGroupForce(projectName, collName), true, projectName, collName);
    }

    public boolean settleWriteGroup(String projectName, String collName) throws IOException {
        try {
            return writeAheadLog.closeGroup(projectName, collName);
        } finally {
            DeferredApplies.flagIfUnapplied(paths, projectName, collName);
        }
    }

    public boolean applyDeferredWrites(String projectName, String collName) throws IOException {
        final var replayed = writeAheadLog.requireApplied(projectName, collName);
        return writeIntentRecovery.recoverBehindAVacuum(projectName, collName) || replayed;
    }

    public void onDeferredApply(DeferredApplyListener listener) {
        DeferredApplies.listenWith(listener);
    }

    public PkRebuild planPkRebuild(String projectName, String collName) throws IOException {
        writeAheadLog.checkpoint(projectName, collName);
        if (pkIndexStore.readRecognisedPkIndex(projectName, collName).isEmpty()) {
            writeIntentRecovery.recoverPagesOf(projectName, collName);
        } else {
            PkIndexRebuild.refuseStaleWriteIntents(writeIntentRecovery, projectName, collName);
        }
        return PkIndexRebuild.plan(paths, pkIndexStore, projectName, collName,
                pageFileLengths(projectName, collName).keySet());
    }

    public void applyPkRebuild(String projectName, String collName, PkRebuild rebuild) throws IOException {
        PkIndexRebuild.apply(pkIndexStore, projectName, collName, rebuild);
    }

    public List<DbEntry> adoptOrphanedRecords(PkRowsSession session) throws IOException {
        return OrphanedPageRecords.adoptAll(paths, session,
                pageFileLengths(session.projectName(), session.collName()).keySet());
    }

    public long pageFileCount(String projectName, String collName) throws IOException {
        return documentPageStore.pageFileCount(projectName, collName);
    }
}

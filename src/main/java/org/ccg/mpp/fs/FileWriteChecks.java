package org.ccg.mpp.fs;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.data.FileEntry;
import org.ccg.mpp.data.FileWriteMode;
import org.ccg.mpp.data.ProjectFilePath;
import org.ccg.mpp.ex.ProjectFileException;
import org.ccg.mpp.ex.ProjectFileException.Reason;

final class FileWriteChecks {
    private final ProjectFileStore store;
    private final FileManifest manifest;

    record MovePlan(List<ProjectFilePath> parents) {
    }

    FileWriteChecks(ProjectFileStore store, FileManifest manifest) {
        this.store = store;
        this.manifest = manifest;
    }

    FileEntry writable(ProjectFilePath path, ProjectFileWrites.WriteSpec spec) throws ProjectFileException {
        if (path.isRoot()) {
            throw new ProjectFileException(Reason.KIND_MISMATCH, "The project's root is a folder, not a file");
        }
        final var existing = manifest.get(path);
        if (spec.ifVersion() != null) {
            final var matches = spec.ifVersion() == 0L
                    ? existing == null
                    : existing != null && existing.version() == spec.ifVersion();
            if (!matches) {
                throw new ProjectFileException(Reason.VERSION_CONFLICT, path + " is at version "
                        + (existing == null ? "0 (absent)" : existing.version()) + ", not " + spec.ifVersion());
            }
        }
        if (existing != null && existing.isDirectory()) {
            throw new ProjectFileException(Reason.KIND_MISMATCH, path + " is a folder, not a file");
        }
        if (existing != null && spec.mode() == FileWriteMode.CREATE) {
            throw new ProjectFileException(Reason.EXISTS, path + " already exists");
        }
        if (existing == null) {
            requireUniqueName(path);
        }
        return existing;
    }

    void requireUniqueName(ProjectFilePath path) throws ProjectFileException {
        final var sibling = manifest.siblingWithFoldedName(path);
        if (sibling != null && !sibling.equals(path.name())) {
            throw new ProjectFileException(Reason.CASE_COLLISION, "'" + sibling + "' already exists in " + path.parent()
                    + " and differs from '" + path.name() + "' only by letter case");
        }
    }

    List<ProjectFilePath> missingParents(ProjectFilePath path, boolean createParents) throws ProjectFileException {
        final var missing = new ArrayList<ProjectFilePath>();
        for (final var ancestor : path.ancestors()) {
            final var entry = manifest.get(ancestor);
            if (entry == null) {
                if (!createParents) {
                    throw new ProjectFileException(Reason.NOT_FOUND, "The folder " + ancestor + " does not exist");
                }
                requireUniqueName(ancestor);
                missing.add(ancestor);
            } else if (!entry.isDirectory()) {
                throw new ProjectFileException(Reason.KIND_MISMATCH, ancestor + " is a file, not a folder");
            }
        }
        return missing;
    }

    void withinLimits(long newSize, FileEntry replaced, int addedEntries) throws ProjectFileException {
        if (newSize > store.maxFileSize()) {
            throw new ProjectFileException(Reason.TOO_LARGE, "The file would hold " + newSize
                    + " bytes, more than filesMaxFileSize (" + store.maxFileSize() + ")");
        }
        final var maxEntries = store.maxEntriesPerProject();
        if (addedEntries > 0 && manifest.entryCount() + (long) addedEntries > maxEntries) {
            throw new ProjectFileException(Reason.QUOTA_EXCEEDED,
                    "The project would hold more than filesMaxEntriesPerProject (" + maxEntries + ") files and"
                            + " folders");
        }
        final var maxBytes = store.maxBytesPerProject();
        final var bytes = manifest.bytes() - (replaced == null ? 0L : replaced.size()) + newSize;
        if (maxBytes > 0 && bytes > maxBytes && newSize > (replaced == null ? 0L : replaced.size())) {
            throw new ProjectFileException(Reason.QUOTA_EXCEEDED, "The project's files would hold " + bytes
                    + " bytes, more than filesMaxBytesPerProject (" + maxBytes + ")");
        }
    }

    MovePlan movable(ProjectFilePath from, ProjectFilePath to, boolean overwrite, boolean createParents)
            throws ProjectFileException {
        if (from.isRoot() || to.isRoot()) {
            throw new ProjectFileException(Reason.INVALID_MOVE, "The project's root cannot be moved or replaced");
        }
        final var source = manifest.get(from);
        if (source == null) {
            throw new ProjectFileException(Reason.NOT_FOUND, "No file or folder at " + from);
        }
        if (from.equals(to)) {
            return null;
        }
        if (to.isWithin(from)) {
            throw new ProjectFileException(Reason.INVALID_MOVE, "A folder cannot be moved into itself");
        }
        final var target = manifest.get(to);
        if (target != null) {
            if (!overwrite) {
                throw new ProjectFileException(Reason.EXISTS, to + " already exists");
            }
            if (target.isDirectory() != source.isDirectory()) {
                throw new ProjectFileException(Reason.KIND_MISMATCH,
                        to + " is a " + kindOf(target) + " and " + from + " a " + kindOf(source));
            }
            if (target.isDirectory() && manifest.hasChildren(to)) {
                throw new ProjectFileException(Reason.NOT_EMPTY, to + " is a folder that is not empty");
            }
        } else {
            final var sibling = manifest.siblingWithFoldedName(to);
            final var renamesItself = from.parent().equals(to.parent()) && from.name().equals(sibling);
            if (!renamesItself) {
                requireUniqueName(to);
            }
        }
        final var parents = missingParents(to, createParents);
        withinLimits(0L, null, parents.size());
        return new MovePlan(parents);
    }

    private static String kindOf(FileEntry entry) {
        return entry.isDirectory() ? "folder" : "file";
    }
}

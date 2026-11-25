package org.ccg.mpp.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.ProjectFilePath;

final class ProjectFileTree {
    private final Path root;
    private final Path metaFolder;

    ProjectFileTree(Path filesRoot, String projectName) {
        this.root = filesRoot.resolve(projectName);
        this.metaFolder = filesRoot.resolve(Globals.FILES_META_FOLDER).resolve(projectName);
    }

    Path root() {
        return root;
    }

    Path metaFolder() {
        return metaFolder;
    }

    Path manifest() {
        return metaFolder.resolve(Globals.FILES_MANIFEST_FILE);
    }

    Path tmpFolder() {
        return metaFolder.resolve(Globals.FILES_TEMP_FOLDER);
    }

    Path resolve(String storedPath) throws IOException {
        return resolve(ProjectFilePath.ofStored(storedPath));
    }

    Path resolve(ProjectFilePath path) throws IOException {
        var current = root;
        if (Files.isSymbolicLink(current)) {
            throw linked(current);
        }
        for (final var segment : path.segments()) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw linked(current);
            }
        }
        return current;
    }

    Path folderOf(Path physical) {
        final var parent = physical.getParent();
        return parent == null ? root : parent;
    }

    private static IOException linked(Path path) {
        return new IOException(path + " is a symbolic link; project files never follow one");
    }

    void ensureFolders() throws IOException {
        OwnerOnly.createDirectories(tmpFolder());
        OwnerOnly.createDirectories(root);
    }
}

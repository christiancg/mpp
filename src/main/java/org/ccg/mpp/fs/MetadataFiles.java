package org.ccg.mpp.fs;

import java.io.IOException;
import java.util.List;
import org.ccg.mpp.config.Globals;

public final class MetadataFiles {
    private final FilePaths paths;

    MetadataFiles(FilePaths paths) {
        this.paths = paths;
    }

    public void writeCollectionSchema(String projectName, String collName, String schemaJson) throws IOException {
        MetadataFileStore.write(paths.schemaFile(projectName, collName), schemaJson);
    }

    public String readCollectionSchema(String projectName, String collName) throws IOException {
        return MetadataFileStore.read(paths.schemaFile(projectName, collName));
    }

    public boolean deleteCollectionSchema(String projectName, String collName) throws IOException {
        return MetadataFileStore.delete(paths.schemaFile(projectName, collName));
    }

    public void writeProcedure(String projectName, String name, String json) throws IOException {
        MetadataFileStore.ensureFolder(paths.proceduresFolder(projectName), "procedures", projectName);
        MetadataFileStore.write(paths.procedureFile(projectName, name), json);
    }

    public String readProcedure(String projectName, String name) throws IOException {
        final var file = paths.procedureFile(projectName, name);
        return MetadataFileStore.isListedExactly(file) ? MetadataFileStore.read(file) : null;
    }

    public boolean deleteProcedure(String projectName, String name) throws IOException {
        final var file = paths.procedureFile(projectName, name);
        return MetadataFileStore.isListedExactly(file) && MetadataFileStore.delete(file);
    }

    public List<String> listProcedureNames(String projectName) throws IOException {
        return MetadataFileStore.listNames(paths.proceduresFolder(projectName), Globals.PROCEDURE_FILE_EXTENSION);
    }

    public void writeSchedule(String projectName, String name, String json) throws IOException {
        MetadataFileStore.ensureFolder(paths.schedulesFolder(projectName), "schedules", projectName);
        MetadataFileStore.write(paths.scheduleFile(projectName, name), json);
    }

    public String readSchedule(String projectName, String name) throws IOException {
        final var file = paths.scheduleFile(projectName, name);
        return MetadataFileStore.isListedExactly(file) ? MetadataFileStore.read(file) : null;
    }

    public boolean deleteSchedule(String projectName, String name) throws IOException {
        final var file = paths.scheduleFile(projectName, name);
        return MetadataFileStore.isListedExactly(file) && MetadataFileStore.delete(file);
    }

    public List<String> listScheduleNames(String projectName) throws IOException {
        return MetadataFileStore.listNames(paths.schedulesFolder(projectName), Globals.SCHEDULE_FILE_EXTENSION);
    }

    public void writeEntrypoint(String projectName, String name, String json) throws IOException {
        MetadataFileStore.ensureFolder(paths.entrypointsFolder(projectName), "entrypoints", projectName);
        MetadataFileStore.write(paths.entrypointFile(projectName, name), json);
    }

    public String readEntrypoint(String projectName, String name) throws IOException {
        final var file = paths.entrypointFile(projectName, name);
        return MetadataFileStore.isListedExactly(file) ? MetadataFileStore.read(file) : null;
    }

    public boolean deleteEntrypoint(String projectName, String name) throws IOException {
        final var file = paths.entrypointFile(projectName, name);
        return MetadataFileStore.isListedExactly(file) && MetadataFileStore.delete(file);
    }

    public List<String> listEntrypointNames(String projectName) throws IOException {
        return MetadataFileStore.listNames(paths.entrypointsFolder(projectName), Globals.ENTRYPOINT_FILE_EXTENSION);
    }

    public void writeTriggers(String projectName, String collName, String json) throws IOException {
        MetadataFileStore.write(paths.triggersFile(projectName, collName), json);
    }

    public String readTriggers(String projectName, String collName) throws IOException {
        return MetadataFileStore.read(paths.triggersFile(projectName, collName));
    }

    public boolean deleteTriggers(String projectName, String collName) throws IOException {
        return MetadataFileStore.delete(paths.triggersFile(projectName, collName));
    }
}

package org.ccg.mpp.ops.admin;

import java.io.IOException;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;

public final class StoredDefinitions {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final EJson eJson = IocContainer.get(EJson.class);

    private StoredDefinitions() {
    }

    public static JsonObject procedure(String projectName, String name) throws IOException {
        return parsed(fs.metadataFiles().readProcedure(projectName, name));
    }

    public static JsonObject schedule(String projectName, String name) throws IOException {
        return parsed(fs.metadataFiles().readSchedule(projectName, name));
    }

    public static JsonObject entrypoint(String projectName, String name) throws IOException {
        return parsed(fs.metadataFiles().readEntrypoint(projectName, name));
    }

    public static JsonObject middleware(String projectName, String name) throws IOException {
        return parsed(fs.metadataFiles().readMiddleware(projectName, name));
    }

    public static JsonObject triggers(String projectName, String collName) throws IOException {
        return parsed(fs.metadataFiles().readTriggers(projectName, collName));
    }

    public static JsonObject schema(String projectName, String collName) throws IOException {
        return parsed(fs.metadataFiles().readCollectionSchema(projectName, collName));
    }

    public static void writeProcedure(String projectName, String name, JsonObject definition) throws IOException {
        fs.metadataFiles().writeProcedure(projectName, name,
                eJson.toJson(restamped(definition, procedureVersion(projectName, name))));
    }

    public static void writeSchedule(String projectName, String name, JsonObject definition) throws IOException {
        fs.metadataFiles().writeSchedule(projectName, name,
                eJson.toJson(restamped(definition, scheduleVersion(projectName, name))));
    }

    public static void writeEntrypoint(String projectName, String name, JsonObject definition) throws IOException {
        fs.metadataFiles().writeEntrypoint(projectName, name,
                eJson.toJson(restamped(definition, entrypointVersion(projectName, name))));
    }

    public static void writeMiddleware(String projectName, String name, JsonObject definition) throws IOException {
        fs.metadataFiles().writeMiddleware(projectName, name,
                eJson.toJson(restamped(definition, middlewareVersion(projectName, name))));
    }

    public static void writeTriggers(String projectName, String collName, JsonObject file) throws IOException {
        fs.metadataFiles().writeTriggers(projectName, collName,
                eJson.toJson(restamped(file, triggersVersion(projectName, collName))));
    }

    public static void writeSchema(String projectName, String collName, JsonObject schema) throws IOException {
        final var version = AdminStamp.over(schemaVersion(projectName, collName));
        fs.metadataFiles().writeCollectionSchema(projectName, collName,
                eJson.toJson(AdminStamp.wrappedSchema(schema, version)));
    }

    private static long triggersVersion(String projectName, String collName) {
        return versionOrZero(() -> triggers(projectName, collName));
    }

    private static long schemaVersion(String projectName, String collName) {
        return versionOrZero(() -> schema(projectName, collName));
    }

    private static long procedureVersion(String projectName, String name) {
        return versionOrZero(() -> procedure(projectName, name));
    }

    private static long scheduleVersion(String projectName, String name) {
        return versionOrZero(() -> schedule(projectName, name));
    }

    private static long entrypointVersion(String projectName, String name) {
        return versionOrZero(() -> entrypoint(projectName, name));
    }

    private static long middlewareVersion(String projectName, String name) {
        return versionOrZero(() -> middleware(projectName, name));
    }

    private static JsonObject restamped(JsonObject definition, long replacedVersion) {
        return AdminStamp.stamped(definition, AdminStamp.over(replacedVersion));
    }

    private interface StoredReader {
        JsonObject read() throws IOException;
    }

    private static long versionOrZero(StoredReader reader) {
        try {
            return AdminStamp.versionOf(reader.read());
        } catch (IOException | RuntimeException unreadable) {
            return 0L;
        }
    }

    private static JsonObject parsed(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return eJson.fromJson(raw, JsonObject.class);
    }
}

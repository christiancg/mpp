package org.ccg.mpp.fs;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.FieldIndexEntry;
import org.ccg.mpp.data.IndexKind;
import org.ccg.mpp.ejson.custom_types.CustomTypeFactory;
import org.ccg.mpp.ejson.elements.JsonBoolean;
import org.ccg.mpp.ejson.elements.JsonCustom;
import org.ccg.mpp.ejson.elements.JsonNull;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.utils.JsonUtils;

public final class LegacyIndexMaintenance {
    private final FileSystem fs;
    private final LegacyIndexLines lines;
    private final String projectName;
    private final String collName;
    private final String fieldName;

    public LegacyIndexMaintenance(FileSystem fs, String projectName, String collName, String fieldName) {
        this.fs = fs;
        this.lines = LegacyIndexLines.of(fs);
        this.projectName = projectName;
        this.collName = collName;
        this.fieldName = fieldName;
    }

    public void apply(String id, DbEntry doc) throws IOException {
        if (doc != null) {
            selectIndexType(doc);
        } else {
            removeIdFromScalarIndexes(id);
            removeIdFromHashIndexes(id);
        }
    }

    private void selectIndexType(DbEntry entry) throws IOException {
        final var element = JsonUtils.getFromPath(entry.getData(), fieldName);
        final var entryId = entry.get_id();
        removeIdFromHashIndexes(entryId);
        if (element != JsonNull.INSTANCE && element.isJsonPrimitive()) {
            final var primitive = element.asJsonPrimitive();
            switch (primitive) {
                case JsonNumber jsonNumber -> findExistingValuesAndUpdate(entryId, jsonNumber.getValue(), Number.class);
                case JsonString jsonString -> {
                    if (jsonString.isJsonCustom()) {
                        findExistingValuesAndUpdate(entryId, CustomTypeFactory.getCustomTypeInstance(jsonString), null);
                    } else {
                        findExistingValuesAndUpdate(entryId, jsonString.getValue(), String.class);
                    }
                }
                case JsonBoolean jsonBoolean ->
                    findExistingValuesAndUpdate(entryId, jsonBoolean.getValue(), Boolean.class);
                default -> throw new IllegalStateException("Unexpected value: " + primitive);
            }
        } else if (element.isJsonObject()) {
            removeIdFromScalarIndexes(entryId);
            addToHashIndex(entryId, IndexKind.OBJECT, JsonUtils.hashElement(element));
        } else if (element.isJsonArray()) {
            removeIdFromScalarIndexes(entryId);
            addToHashIndex(entryId, IndexKind.ARRAY, JsonUtils.hashElement(element));
        } else {
            removeIdFromScalarIndexes(entryId);
        }
    }

    private void addToHashIndex(String entryId, IndexKind kind, String hash) throws IOException {
        final var entries = fs.readWholeHashIndexFile(projectName, collName, fieldName, kind);
        final var found = entries == null
                ? null
                : entries.stream().filter(e -> e.getValue().equals(hash)).findFirst().orElse(null);
        if (found != null) {
            found.getIds().add(entryId);
            lines.updateHashIndexFiles(projectName, collName, fieldName, kind, found, null);
        } else {
            final var indexEntry = new FieldIndexEntry<>(projectName, collName, hash, new HashSet<>(Set.of(entryId)));
            lines.updateHashIndexFiles(projectName, collName, fieldName, kind, indexEntry, null);
        }
    }

    private void removeIdFromHashIndexes(String entryId) throws IOException {
        for (var kind : IndexKind.HASH_KINDS) {
            final var entries = fs.readWholeHashIndexFile(projectName, collName, fieldName, kind);
            if (entries != null) {
                for (var entry : entries) {
                    if (entry.getIds().remove(entryId)) {
                        lines.updateHashIndexFiles(projectName, collName, fieldName, kind, null, entry);
                        break;
                    }
                }
            }
        }
    }

    private void removeIdFromScalarIndexes(String entryId) throws IOException {
        for (var type : List.of(Boolean.class, Number.class, String.class)) {
            removeScalarOfType(entryId, type);
        }
        for (var customType : CustomTypeFactory.getCustomTypes().values()) {
            removeScalarOfType(entryId, customType);
        }
    }

    private <T> void removeScalarOfType(String entryId, Class<T> type) throws IOException {
        final var removed = existingEntry(entryId, type);
        if (removed != null) {
            lines.updateIndexFiles(projectName, collName, fieldName, null, removed);
        }
    }

    private <T> void findExistingValuesAndUpdate(String entryId, T value, Class<T> tClass) throws IOException {
        FieldIndexEntry<?> toRemove = existingEntry(entryId, Boolean.class);
        if (toRemove == null) {
            toRemove = existingEntry(entryId, Number.class);
        }
        if (toRemove == null) {
            toRemove = existingEntry(entryId, String.class);
        }
        if (toRemove == null) {
            for (var customType : CustomTypeFactory.getCustomTypes().values()) {
                toRemove = existingEntry(entryId, customType);
                if (toRemove != null) {
                    break;
                }
            }
        }
        final FieldIndexEntry<?> found = value instanceof JsonCustom<?> custom
                ? matchingCustom(custom)
                : matching(value, tClass);
        if (found != null) {
            found.getIds().add(entryId);
            lines.updateIndexFiles(projectName, collName, fieldName, found, toRemove);
        } else {
            lines.updateIndexFiles(projectName, collName, fieldName,
                    new FieldIndexEntry<>(projectName, collName, value, new HashSet<>(Set.of(entryId))), toRemove);
        }
    }

    private FieldIndexEntry<?> matchingCustom(JsonCustom<?> value) throws IOException {
        final var indexEntries = fs.readWholeFieldIndexFiles(projectName, collName, fieldName, value.getClass());
        if (indexEntries == null) {
            return null;
        }
        return indexEntries.stream().filter(indexEntry -> indexEntry.getValue().getValue().equals(value.getValue()))
                .findFirst().orElse(null);
    }

    private <T> FieldIndexEntry<T> matching(T value, Class<T> tClass) throws IOException {
        final var indexEntries = fs.readWholeFieldIndexFiles(projectName, collName, fieldName, tClass);
        if (indexEntries == null) {
            return null;
        }
        return indexEntries.stream()
                .filter(indexEntry -> FieldIndexEntry.sameIndexedValue(indexEntry.getValue(), value)).findFirst()
                .orElse(null);
    }

    private <T> FieldIndexEntry<T> existingEntry(String entityId, Class<T> tClass) throws IOException {
        final var entries = fs.readWholeFieldIndexFiles(projectName, collName, fieldName, tClass);
        if (entries == null) {
            return null;
        }
        return entries.stream().filter(entry -> entry.getIds().contains(entityId)).findFirst().map(entry -> {
            entry.getIds().remove(entityId);
            return entry;
        }).orElse(null);
    }
}

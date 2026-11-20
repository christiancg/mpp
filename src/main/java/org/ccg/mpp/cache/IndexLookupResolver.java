package org.ccg.mpp.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.data.FieldIndexEntry;
import org.ccg.mpp.data.IndexKind;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonBoolean;
import org.ccg.mpp.ejson.elements.JsonCustom;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.req.agg.FieldOperatorType;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;
import org.ccg.mpp.utils.JsonUtils;
import org.ccg.mpp.utils.RangeBounds;
import org.ccg.mpp.utils.SearchUtils;

final class IndexLookupResolver {
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final ResourceLocking locking = IocContainer.get(ResourceLocking.class);

    private IndexLookupResolver() {
    }

    static <T> Set<String> doGetIdsFromIndex(UserCache userCache, String projectName, String collName, String fieldName,
            FieldOperator operator, T value) throws IOException {
        final var opType = operator.getFieldOperatorType();
        return switch (value) {
            case JsonObject obj -> isEquality(opType)
                    ? hashLookup(userCache, projectName, collName, fieldName, opType, IndexKind.OBJECT, obj)
                    : null;
            case JsonArray arr -> switch (opType) {
                case EQUALS, NOT_EQUALS ->
                    hashLookup(userCache, projectName, collName, fieldName, opType, IndexKind.ARRAY, arr);
                case IN, NOT_IN -> isHeterogeneous(arr)
                        ? null
                        : getIdsFromInList(userCache, projectName, collName, fieldName, opType, arr);
                default -> null;
            };
            default -> scalarLookup(userCache, projectName, collName, fieldName, opType, value);
        };
    }

    static Set<String> idsInRange(UserCache userCache, String projectName, String collName, RangeBounds bounds)
            throws IOException {
        final var fieldName = bounds.fieldName();
        return readingIndex(projectName, collName, fieldName, () -> {
            if (IndexFileReads.fromFile(userCache, projectName, collName, fieldName, Number.class)) {
                return recorded(userCache, projectName, collName, fieldName,
                        IndexFileReads.rangeIds(projectName, collName, bounds));
            }
            final var index = userCache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName, Number.class);
            if (index == null) {
                return null;
            }
            final var ids = SearchUtils.findingBetween(index, bounds);
            userCache.recordFieldIndexAccess(projectName, collName, fieldName);
            return ids;
        });
    }

    static OptionalLong countInRange(UserCache userCache, String projectName, String collName, RangeBounds bounds)
            throws IOException {
        final var fieldName = bounds.fieldName();
        return readingIndex(projectName, collName, fieldName, () -> {
            if (IndexFileReads.fromFile(userCache, projectName, collName, fieldName, Number.class)) {
                final var count = IndexFileReads.rangeCount(projectName, collName, bounds);
                if (count.isPresent()) {
                    userCache.recordFieldIndexAccess(projectName, collName, fieldName);
                }
                return count;
            }
            final var index = userCache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName, Number.class);
            if (index == null) {
                return OptionalLong.empty();
            }
            userCache.recordFieldIndexAccess(projectName, collName, fieldName);
            return OptionalLong.of(SearchUtils.countBetween(index, bounds));
        });
    }

    static <T> OptionalLong countEquals(UserCache userCache, String projectName, String collName, String fieldName,
            T value) throws IOException {
        return countEqualsAny(userCache, projectName, collName, fieldName, List.of(value));
    }

    @SuppressWarnings("unchecked")
    static OptionalLong countEqualsAny(UserCache userCache, String projectName, String collName, String fieldName,
            List<?> values) throws IOException {
        final var type = (Class<Object>) commonScalarType(values);
        if (type == null) {
            return OptionalLong.empty();
        }
        return readingIndex(projectName, collName, fieldName, () -> {
            if (IndexFileReads.fromFile(userCache, projectName, collName, fieldName, type)) {
                final var count = IndexFileReads.anyOfCount(projectName, collName, fieldName, type, values);
                if (count.isPresent()) {
                    userCache.recordFieldIndexAccess(projectName, collName, fieldName);
                }
                return count;
            }
            final var index = userCache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName, type);
            if (index == null) {
                return OptionalLong.empty();
            }
            userCache.recordFieldIndexAccess(projectName, collName, fieldName);
            return OptionalLong.of(SearchUtils.countAnyOf(index, (List<Object>) values));
        });
    }

    @SuppressWarnings("unchecked")
    static Set<String> idsEqualToAny(UserCache userCache, String projectName, String collName, String fieldName,
            List<Object> lookupValues) throws IOException {
        return readingIndex(projectName, collName, fieldName, () -> {
            final var byType = new IdentityHashMap<Class<?>, List<Object>>();
            for (final var value : lookupValues) {
                byType.computeIfAbsent(IndexKind.scalarIndexClassOf(value), _ -> new ArrayList<>()).add(value);
            }
            final var ids = new HashSet<String>();
            for (final var typed : byType.entrySet()) {
                final var found = idsEqualToAnyOfType(userCache, projectName, collName, fieldName,
                        (Class<Object>) typed.getKey(), typed.getValue());
                if (found == null) {
                    return null;
                }
                ids.addAll(found);
            }
            userCache.recordFieldIndexAccess(projectName, collName, fieldName);
            return ids;
        });
    }

    private static Set<String> idsEqualToAnyOfType(UserCache userCache, String projectName, String collName,
            String fieldName, Class<Object> type, List<Object> values) throws IOException {
        if (IndexFileReads.fromFile(userCache, projectName, collName, fieldName, type)) {
            return IndexFileReads.anyOfIds(projectName, collName, fieldName, type, values);
        }
        final var index = userCache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName, type);
        if (index == null) {
            return null;
        }
        final var ids = new HashSet<String>();
        for (final var value : values) {
            ids.addAll(SearchUtils.findingByOperator(index, FieldOperatorType.EQUALS, value));
        }
        return ids;
    }

    private static Class<?> commonScalarType(List<?> values) {
        Class<?> type = null;
        for (final var value : values) {
            if (value == null) {
                return null;
            }
            final var valueType = IndexKind.scalarIndexClassOf(value);
            if (type != null && type != valueType) {
                return null;
            }
            type = valueType;
        }
        return type;
    }

    private static Set<String> recorded(UserCache userCache, String projectName, String collName, String fieldName,
            Set<String> ids) {
        if (ids != null) {
            userCache.recordFieldIndexAccess(projectName, collName, fieldName);
        }
        return ids;
    }

    @FunctionalInterface
    private interface IndexRead<R> {
        R read() throws IOException;
    }

    private static <R> R readingIndex(String projectName, String collName, String fieldName, IndexRead<R> read)
            throws IOException {
        try {
            locking.lockIndexRead(projectName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        try {
            return read.read();
        } finally {
            locking.releaseIndexRead(projectName, collName, fieldName);
        }
    }

    private static boolean isEquality(FieldOperatorType opType) {
        return opType == FieldOperatorType.EQUALS || opType == FieldOperatorType.NOT_EQUALS;
    }

    private static Set<String> hashLookup(UserCache userCache, String projectName, String collName, String fieldName,
            FieldOperatorType opType, IndexKind kind, JsonBaseElement value) throws IOException {
        final var hash = JsonUtils.hashElement(value);
        if (isComplement(opType)) {
            return complement(userCache, projectName, collName, fieldName, null, kind, List.of(hash));
        }
        final var hashIndex = userCache.getHashIndexAndLoadIfNecessary(projectName, collName, fieldName, kind);
        return hashIndex != null ? SearchUtils.findingByOperator(hashIndex, opType, hash) : null;
    }

    @SuppressWarnings("unchecked")
    private static <T> Set<String> scalarLookup(UserCache userCache, String projectName, String collName,
            String fieldName, FieldOperatorType opType, T value) throws IOException {
        if (IndexKind.valueKeysCandidatesOnly(value) && opType != FieldOperatorType.EQUALS) {
            return null;
        }
        final var type = (Class<T>) IndexKind.scalarIndexClassOf(value);
        if (isComplement(opType)) {
            return complement(userCache, projectName, collName, fieldName, type, null, List.of(value));
        }
        if (IndexFileReads.fromFile(userCache, projectName, collName, fieldName, type)) {
            return IndexFileReads.windowIds(projectName, collName, fieldName, type, opType, value);
        }
        final var index = userCache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName, type);
        if (index == null || (opType == FieldOperatorType.CONTAINS && value instanceof String
                && hasAnotherIndex(userCache, projectName, collName, fieldName, String.class, null))) {
            return null;
        }
        return SearchUtils.findingByOperator(index, opType, value);
    }

    private static boolean isHeterogeneous(JsonArray arr) {
        if (arr.isEmpty()) {
            return false;
        }
        final var first = kindOf(arr.get(0));
        for (final var element : arr.asList()) {
            if (!first.equals(kindOf(element))) {
                return true;
            }
        }
        return false;
    }

    private static String kindOf(JsonBaseElement element) {
        if (element.isJsonObject()) {
            return "object";
        }
        if (element.isJsonArray()) {
            return "array";
        }
        if (element.isJsonPrimitive()) {
            final var primitive = element.asJsonPrimitive();
            if (primitive instanceof JsonCustom<?> custom) {
                return "custom:" + custom.getCustomTypeName();
            }
            if (primitive instanceof JsonString) {
                return "string";
            }
            if (primitive instanceof JsonNumber) {
                return "number";
            }
            if (primitive instanceof JsonBoolean) {
                return "boolean";
            }
        }
        return "other";
    }

    private static boolean hasAnotherIndex(UserCache userCache, String projectName, String collName, String fieldName,
            Class<?> chosenType, IndexKind chosenKind) throws IOException {
        for (final var type : IndexKind.scalarIndexClasses()) {
            if (type != chosenType && IndexFileReads.typeExists(userCache, projectName, collName, fieldName, type)) {
                return true;
            }
        }
        for (final var kind : IndexKind.HASH_KINDS) {
            if (kind != chosenKind && IndexFileReads.kindExists(userCache, projectName, collName, fieldName, kind)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isComplement(FieldOperatorType opType) {
        return opType == FieldOperatorType.NOT_IN || opType == FieldOperatorType.NOT_EQUALS;
    }

    @SuppressWarnings("unchecked")
    private static <T> Set<String> complement(UserCache userCache, String projectName, String collName,
            String fieldName, Class<T> type, IndexKind kind, List<T> operands) throws IOException {
        if (hasAnotherIndex(userCache, projectName, collName, fieldName, type, kind)
                || !pendingIndexWrites.idsFor(projectName, collName).isEmpty()) {
            return null;
        }
        final long documents = userCache.pkCountForCoverage(projectName, collName);
        if (documents < 0) {
            return null;
        }
        if (type != null && IndexFileReads.fromFile(userCache, projectName, collName, fieldName, type)) {
            return IndexFileReads.idCount(projectName, collName, fieldName, type) == documents
                    ? IndexFileReads.complementIds(projectName, collName, fieldName, type, operands)
                    : null;
        }
        final var index = type != null
                ? userCache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName, type)
                : (List<FieldIndexEntry<T>>) (List<?>) userCache.getHashIndexAndLoadIfNecessary(projectName, collName,
                        fieldName, kind);
        return index != null && SearchUtils.idCount(index) == documents
                ? SearchUtils.findingInNotIn(index, FieldOperatorType.NOT_IN, operands)
                : null;
    }

    @SuppressWarnings("unchecked")
    private static <T> Set<String> getIdsFromInList(UserCache userCache, String projectName, String collName,
            String fieldName, FieldOperatorType opType, JsonArray arr) throws IOException {
        if (arr.isEmpty()) {
            return null;
        }
        final var first = arr.get(0);
        if (first.isJsonObject() || first.isJsonArray()) {
            final var kind = first.isJsonObject() ? IndexKind.OBJECT : IndexKind.ARRAY;
            final var hashes = arr.asList().stream().map(JsonUtils::hashElement).toList();
            if (opType == FieldOperatorType.NOT_IN) {
                return complement(userCache, projectName, collName, fieldName, null, kind, hashes);
            }
            final var hashIndex = userCache.getHashIndexAndLoadIfNecessary(projectName, collName, fieldName, kind);
            return hashIndex != null ? SearchUtils.findingInNotIn(hashIndex, opType, hashes) : null;
        }
        final var firstValue = indexedValue(first);
        if (firstValue == null || IndexKind.valueKeysCandidatesOnly(firstValue) && opType != FieldOperatorType.IN) {
            return null;
        }
        final var type = (Class<T>) IndexKind.scalarIndexClassOf(firstValue);
        final var values = (List<T>) arr.asList().stream().map(IndexLookupResolver::indexedValue).toList();
        if (opType == FieldOperatorType.NOT_IN) {
            return complement(userCache, projectName, collName, fieldName, type, null, values);
        }
        if (IndexFileReads.fromFile(userCache, projectName, collName, fieldName, type)) {
            return IndexFileReads.anyOfIds(projectName, collName, fieldName, type, values);
        }
        final var index = userCache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName, type);
        return index != null ? SearchUtils.findingInNotIn(index, opType, values) : null;
    }

    private static Object indexedValue(JsonBaseElement element) {
        if (!element.isJsonPrimitive()) {
            return null;
        }
        return switch (element.asJsonPrimitive()) {
            case JsonCustom<?> custom -> custom;
            case JsonString string -> string.getValue();
            case JsonNumber number -> number.getValue();
            case JsonBoolean bool -> bool.getValue();
            default -> null;
        };
    }
}

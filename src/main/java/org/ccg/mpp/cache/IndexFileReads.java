package org.ccg.mpp.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.IndexKind;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.IndexFileLookup;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.req.agg.FieldOperatorType;
import org.ccg.mpp.utils.RangeBounds;

final class IndexFileReads {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final IndexIdCounts idCounts = IocContainer.get(IndexIdCounts.class);

    private IndexFileReads() {
    }

    static boolean fromFile(UserCache userCache, String projectName, String collName, String fieldName, Class<?> type) {
        if (!IndexFileLookup.supports(type)) {
            return false;
        }
        final var identifier = Cache.memoizedIndexIdentifier(fieldName, type);
        return userCache.residentFieldIndex(projectName, collName, identifier) == null
                && cache.isFieldIndexTypeRefused(projectName, collName, identifier)
                && shouldServeFromFile(projectName, collName, identifier);
    }

    private static boolean shouldServeFromFile(String projectName, String collName, String indexIdentifier) {
        return !cache.displacesColderIndexes(projectName, collName, indexIdentifier);
    }

    static boolean reloadsLargeIndex(UserCache userCache, String projectName, String collName, String fieldName) {
        for (final var type : IndexKind.scalarIndexClasses()) {
            final var identifier = Cache.memoizedIndexIdentifier(fieldName, type);
            if (userCache.residentFieldIndex(projectName, collName, identifier) == null
                    && !cache.isFieldIndexTypeAbsent(projectName, collName, identifier)
                    && reloadsLargeIndex(userCache, projectName, collName, fieldName, type, identifier)) {
                return true;
            }
        }
        return false;
    }

    private static boolean reloadsLargeIndex(UserCache userCache, String projectName, String collName, String fieldName,
            Class<?> type, String identifier) {
        final var bytes = fs.indexFileBytes(projectName, collName, fieldName, IndexKind.fileLabel(type));
        if (bytes <= Globals.TRANSIENT_SORT_WALK_MAX_INDEX_BYTES) {
            return false;
        }
        return cache.isFieldIndexTypeRefused(projectName, collName, identifier) || !userCache.loadsAdmitted(projectName,
                collName, AccessKind.FIELD_INDEX, CacheSizeEstimator.estimateFieldIndexSizeFromFileBytes(bytes));
    }

    static boolean typeExists(UserCache userCache, String projectName, String collName, String fieldName, Class<?> type)
            throws IOException {
        final var identifier = Cache.memoizedIndexIdentifier(fieldName, type);
        if (userCache.residentFieldIndex(projectName, collName, identifier) != null) {
            return true;
        }
        return cache.isFieldIndexTypeRefused(projectName, collName, identifier)
                ? fs.indexTypeFileExists(projectName, collName, fieldName, IndexKind.fileLabel(type))
                : userCache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName, type) != null;
    }

    static boolean kindExists(UserCache userCache, String projectName, String collName, String fieldName,
            IndexKind kind) throws IOException {
        final var identifier = Cache.memoizedHashIndexIdentifier(fieldName, kind);
        if (userCache.residentFieldIndex(projectName, collName, identifier) != null) {
            return true;
        }
        return cache.isFieldIndexTypeRefused(projectName, collName, identifier)
                ? fs.indexTypeFileExists(projectName, collName, fieldName, kind.label())
                : userCache.getHashIndexAndLoadIfNecessary(projectName, collName, fieldName, kind) != null;
    }

    static Set<String> anyOfIds(String projectName, String collName, String fieldName, Class<?> type, List<?> values)
            throws IOException {
        return fs.indexFiles().equalsIds(projectName, collName, fieldName, type, values);
    }

    static Set<String> windowIds(String projectName, String collName, String fieldName, Class<?> type,
            FieldOperatorType opType, Object value) throws IOException {
        return switch (opType) {
            case EQUALS -> anyOfIds(projectName, collName, fieldName, type, List.of(value));
            case GREATER_THAN, GREATER_THAN_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS ->
                fs.indexFiles().rangeIds(projectName, collName, fieldName, type, List.of(boundOf(opType, value)));
            default -> null;
        };
    }

    static Set<String> rangeIds(String projectName, String collName, RangeBounds bounds) throws IOException {
        return fs.indexFiles().rangeIds(projectName, collName, bounds.fieldName(), Number.class, fileBounds(bounds));
    }

    static OptionalLong rangeCount(String projectName, String collName, RangeBounds bounds) throws IOException {
        return known(fs.indexFiles().rangeCount(projectName, collName, bounds.fieldName(), Number.class,
                fileBounds(bounds)));
    }

    static OptionalLong anyOfCount(String projectName, String collName, String fieldName, Class<?> type, List<?> values)
            throws IOException {
        return known(fs.indexFiles().equalsCount(projectName, collName, fieldName, type, values));
    }

    private static OptionalLong known(long count) {
        return count < 0 ? OptionalLong.empty() : OptionalLong.of(count);
    }

    private static List<IndexFileLookup.Bound> fileBounds(RangeBounds bounds) {
        final var fileBounds = new ArrayList<IndexFileLookup.Bound>(bounds.bounds().size());
        for (final var bound : bounds.bounds()) {
            fileBounds.add(boundOf(bound.operatorType(), bound.value()));
        }
        return fileBounds;
    }

    static Set<String> complementIds(String projectName, String collName, String fieldName, Class<?> type,
            List<?> values) throws IOException {
        return fs.indexFiles().complementIds(projectName, collName, fieldName, type, values);
    }

    static long idCount(String projectName, String collName, String fieldName, Class<?> type) throws IOException {
        return idCounts.count(projectName, collName, Cache.memoizedIndexIdentifier(fieldName, type),
                () -> fs.indexFiles().idCount(projectName, collName, fieldName, type));
    }

    private static IndexFileLookup.Bound boundOf(FieldOperatorType opType, Object value) {
        return switch (opType) {
            case GREATER_THAN -> new IndexFileLookup.Bound(value, true, false);
            case GREATER_THAN_EQUALS -> new IndexFileLookup.Bound(value, true, true);
            case SMALLER_THAN -> new IndexFileLookup.Bound(value, false, false);
            case SMALLER_THAN_EQUALS -> new IndexFileLookup.Bound(value, false, true);
            default -> throw new IllegalArgumentException("Not a range bound: " + opType);
        };
    }
}

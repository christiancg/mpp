package org.ccg.mpp.simplejs.builtins;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonBoolean;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.simplejs.exceptions.RangeErrorException;
import org.ccg.mpp.simplejs.exceptions.TypeErrorException;
import org.ccg.mpp.simplejs.host.BulkSaveOutcome;
import org.ccg.mpp.simplejs.host.ProjectAccess;
import org.ccg.mpp.simplejs.host.ResourceLimits;
import org.ccg.mpp.simplejs.internal.JsCoercion;
import org.ccg.mpp.simplejs.values.EJsonInterop;
import org.ccg.mpp.simplejs.values.JsArray;
import org.ccg.mpp.simplejs.values.JsFunction;
import org.ccg.mpp.simplejs.values.JsNativeFunction;
import org.ccg.mpp.simplejs.values.JsNumber;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsPromise;
import org.ccg.mpp.simplejs.values.JsString;
import org.ccg.mpp.simplejs.values.JsUndefined;
import org.ccg.mpp.simplejs.values.JsValue;

public final class DbModule {
    private static final int PAYLOAD_INDEX = 2;
    private static final int PATCH_INDEX = 3;
    private static final String PK_FIELD = "_id";
    private static final String PROJECT_NAME = "project name";
    private static final String COLLECTION_NAME = "collection name";
    private static final String ID = "id";

    private DbModule() {
    }

    @FunctionalInterface
    private interface DocumentWrite {
        JsonObject apply(String db, String coll, JsonObject document);
    }

    public static JsObject create(ProjectAccess project, InterpreterOps ops, Intrinsics intrinsics,
            ResourceLimits limits) {
        final var db = new JsObject();
        db.set("findById", hostCall("findById", ops, args -> findById(project, ops, args)));
        db.set("aggregate", hostCall("aggregate", ops, args -> aggregate(project, ops, args)));
        db.set("save", hostCall("save", ops, args -> write("save", project::save, ops, args)));
        db.set("insert", hostCall("insert", ops, args -> write("insert", project::insert, ops, args)));
        db.set("bulkSave", hostCall("bulkSave", ops, args -> bulkSave(project, ops, args)));
        db.set("cursor", hostCall("cursor", ops, args -> cursor(project, ops, intrinsics, limits, args)));
        db.set("delete", hostCall("delete", ops, args -> {
            project.delete(requireString(args, 0, "delete", PROJECT_NAME),
                    requireString(args, 1, "delete", COLLECTION_NAME), requireString(args, 2, "delete", ID));
            return JsUndefined.getInstance();
        }));
        db.set("deleteWhere", hostCall("deleteWhere", ops, args -> deleteWhere(project, ops, args)));
        db.set("updateWhere", hostCall("updateWhere", ops, args -> updateWhere(project, ops, args)));
        db.set("listCollections", hostCall("listCollections", ops, args -> toStringArray(
                project.listCollections(requireString(args, 0, "listCollections", PROJECT_NAME)))));
        db.set("listProjects", hostCall("listProjects", ops, _ -> toStringArray(project.listProjects())));
        db.set("transaction", hostCall("transaction", ops, args -> transaction(project, ops, args)));
        final var scopedProject = project.scopedProject();
        if (scopedProject != null) {
            db.set("name", new JsString(scopedProject));
        }
        return db;
    }

    private static JsNativeFunction hostCall(String name, InterpreterOps ops, Function<List<JsValue>, JsValue> body) {
        return new JsNativeFunction(name, (_, args) -> NativeDepth.withHeadroom(ops, () -> body.apply(args)));
    }

    private static JsValue findById(ProjectAccess project, InterpreterOps ops, List<JsValue> args) {
        final var document = project.findById(requireString(args, 0, "findById", PROJECT_NAME),
                requireString(args, 1, "findById", COLLECTION_NAME), requireString(args, 2, "findById", ID));
        InterpreterOps.charge(ops, EJsonInterop.estimatedBytes(document));
        return EJsonInterop.fromEjson(document);
    }

    private static JsValue aggregate(ProjectAccess project, InterpreterOps ops, List<JsValue> args) {
        final var projectName = requireString(args, 0, "aggregate", PROJECT_NAME);
        final var collName = requireString(args, 1, "aggregate", COLLECTION_NAME);
        final var pipeline = requireArray(payloadArg(args, ops), "db.aggregate expects an array of aggregation steps");
        final var results = project.aggregate(projectName, collName, pipeline);
        final var array = new JsArray();
        for (final var result : results) {
            InterpreterOps.charge(ops, EJsonInterop.estimatedBytes(result));
            array.push(EJsonInterop.fromEjson(result));
        }
        return array;
    }

    private static JsValue write(String method, DocumentWrite access, InterpreterOps ops, List<JsValue> args) {
        final var projectName = requireString(args, 0, method, PROJECT_NAME);
        final var collName = requireString(args, 1, method, COLLECTION_NAME);
        final var document = requireObject(payloadArg(args, ops), "db." + method + " expects a document");
        final var saved = access.apply(projectName, collName, document);
        InterpreterOps.charge(ops, EJsonInterop.estimatedBytes(saved));
        return EJsonInterop.fromEjson(saved);
    }

    private static JsValue bulkSave(ProjectAccess project, InterpreterOps ops, List<JsValue> args) {
        final var projectName = requireString(args, 0, "bulkSave", PROJECT_NAME);
        final var collName = requireString(args, 1, "bulkSave", COLLECTION_NAME);
        final var array = requireArray(payloadArg(args, ops), "db.bulkSave expects an array of documents");
        final var documents = new ArrayList<JsonObject>();
        for (final var element : array) {
            if (!(element instanceof JsonObject document)) {
                throw new TypeErrorException("db.bulkSave expects an array of documents");
            }
            documents.add(document);
        }
        final var result = project.bulkSave(projectName, collName, documents);
        InterpreterOps.chargeElements(ops, (long) result.inserted().size() + result.updated().size());
        return outcome(result);
    }

    private static JsValue deleteWhere(ProjectAccess project, InterpreterOps ops, List<JsValue> args) {
        final var projectName = requireString(args, 0, "deleteWhere", PROJECT_NAME);
        final var collName = requireString(args, 1, "deleteWhere", COLLECTION_NAME);
        final var condition = requireObject(payloadArg(args, ops), "db.deleteWhere expects a condition object");
        return new JsNumber(project.deleteWhere(projectName, collName, condition));
    }

    private static JsValue updateWhere(ProjectAccess project, InterpreterOps ops, List<JsValue> args) {
        final var projectName = requireString(args, 0, "updateWhere", PROJECT_NAME);
        final var collName = requireString(args, 1, "updateWhere", COLLECTION_NAME);
        final var condition = requireObject(payloadArg(args, ops), "db.updateWhere expects a condition object");
        final var patch = requireObject(
                args.size() > PATCH_INDEX ? EJsonInterop.toHostPayload(args.get(PATCH_INDEX), ops) : null,
                "db.updateWhere expects an object of fields to set");
        return new JsNumber(project.updateWhere(projectName, collName, condition, patch));
    }

    private static JsValue cursor(ProjectAccess project, InterpreterOps ops, Intrinsics intrinsics,
            ResourceLimits limits, List<JsValue> args) {
        final var projectName = requireString(args, 0, "cursor", PROJECT_NAME);
        final var collName = requireString(args, 1, "cursor", COLLECTION_NAME);
        final var converted = args.size() > 2 ? EJsonInterop.toHostEjson(args.get(2), ops) : null;
        if (!(converted instanceof JsonArray steps)) {
            throw new TypeErrorException("db.cursor expects an array of aggregation steps");
        }
        final var iterator = JsIterators
                .of(new BatchIterator(project, ops, projectName, collName, steps, batchSize(args, limits)));
        return JsIterators.linkPrototype(iterator, intrinsics == null ? null : intrinsics.dbCursorProto);
    }

    private static int batchSize(List<JsValue> args, ResourceLimits limits) {
        final var max = limits == null || limits.cursorMaxBatchSize() < 1
                ? ResourceLimits.DEFAULT_CURSOR_MAX_BATCH_SIZE
                : limits.cursorMaxBatchSize();
        final var options = args.size() > 3 ? args.get(3) : JsUndefined.getInstance();
        if (!(options instanceof JsObject object) || !object.has("batchSize")) {
            final var configured = limits == null || limits.cursorBatchSize() < 1
                    ? ResourceLimits.DEFAULT_CURSOR_BATCH_SIZE
                    : limits.cursorBatchSize();
            return Math.min(configured, max);
        }
        final var requested = JsCoercion.toNumber(object.get("batchSize"));
        if (Double.isNaN(requested) || requested < 1) {
            throw new RangeErrorException("db.cursor batchSize must be a number greater than or equal to 1");
        }
        return (int) Math.min(requested, max);
    }

    private static final class BatchIterator implements Iterator<JsValue> {
        private final ProjectAccess project;
        private final InterpreterOps ops;
        private final String projectName;
        private final String collName;
        private final JsonArray steps;
        private final int batchSize;
        private final Deque<JsValue> buffer = new ArrayDeque<>();
        private int skip;
        private boolean exhausted;
        private long batchBytes;

        private BatchIterator(ProjectAccess project, InterpreterOps ops, String projectName, String collName,
                JsonArray steps, int batchSize) {
            this.project = project;
            this.ops = ops;
            this.projectName = projectName;
            this.collName = collName;
            final var cut = project.firstUnorderedCut(projectName, collName, steps);
            final var anchored = cut < 0 ? steps : sortedByIdAt(steps, cut);
            this.steps = project.ordersResults(projectName, collName, anchored)
                    ? anchored
                    : sortedByIdAt(anchored, anchored.size());
            this.batchSize = batchSize;
        }

        private static JsonArray sortedByIdAt(JsonArray steps, int index) {
            final var sort = new JsonObject();
            sort.add("type", new JsonString("SORT"));
            sort.add("fieldName", new JsonString(PK_FIELD));
            sort.add("ascending", JsonBoolean.of(true));
            final var ordered = new JsonArray();
            for (var i = 0; i < steps.size(); i++) {
                if (i == index) {
                    ordered.add(sort);
                }
                ordered.add(steps.get(i));
            }
            if (index >= steps.size()) {
                ordered.add(sort);
            }
            return ordered;
        }

        @Override
        public boolean hasNext() {
            if (buffer.isEmpty() && !exhausted) {
                fetch();
            }
            return !buffer.isEmpty();
        }

        @Override
        public JsValue next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return buffer.removeFirst();
        }

        private void fetch() {
            NativeDepth.requireHeadroom(ops);
            InterpreterOps.release(ops, batchBytes);
            batchBytes = 0;
            final var results = project.aggregate(projectName, collName, paged());
            for (final var result : results) {
                final var bytes = EJsonInterop.estimatedBytes(result);
                InterpreterOps.charge(ops, bytes);
                batchBytes += bytes;
                buffer.addLast(EJsonInterop.fromEjson(result));
            }
            skip += results.size();
            exhausted = results.size() < batchSize;
        }

        private JsonArray paged() {
            final var pipeline = new JsonArray();
            pipeline.addAll(steps);
            pipeline.add(step("SKIP", "skip", skip));
            pipeline.add(step("LIMIT", "limit", batchSize));
            return pipeline;
        }

        private static JsonObject step(String type, String field, int value) {
            final var step = new JsonObject();
            step.add("type", new JsonString(type));
            step.add(field, new JsonNumber(value));
            return step;
        }
    }

    private static JsValue outcome(BulkSaveOutcome result) {
        final var object = new JsObject();
        object.set("inserted", toStringArray(result.inserted()));
        object.set("updated", toStringArray(result.updated()));
        return object;
    }

    private static JsValue transaction(ProjectAccess project, InterpreterOps ops, List<JsValue> args) {
        final var callback = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        requireSynchronousCallable(callback);
        project.beginTransaction();
        JsValue result;
        try {
            result = ops.call(callback, JsUndefined.getInstance(), List.of());
            rejectThenable(result);
        } catch (Throwable e) {
            project.rollbackTransaction();
            throw e;
        }
        project.commitTransaction();
        return result;
    }

    private static void requireSynchronousCallable(JsValue callback) {
        if (callback instanceof JsFunction function) {
            if (function.isAsync() || function.isGenerator()) {
                throw new TypeErrorException("db.transaction callback must be a synchronous function");
            }
            return;
        }
        if (!(callback instanceof JsNativeFunction)) {
            throw new TypeErrorException("db.transaction callback is not a function");
        }
    }

    private static void rejectThenable(JsValue result) {
        if (result instanceof JsPromise) {
            throw new TypeErrorException("db.transaction callback must not return a promise");
        }
    }

    private static JsValue toStringArray(List<String> values) {
        final var array = new JsArray();
        for (final var value : values) {
            array.push(new JsString(value));
        }
        return array;
    }

    private static String requireString(List<JsValue> args, int index, String method, String role) {
        if (index < args.size() && args.get(index) instanceof JsString string) {
            return string.getValue();
        }
        throw new TypeErrorException("db." + method + " expects a string " + role);
    }

    private static JsonBaseElement payloadArg(List<JsValue> args, InterpreterOps ops) {
        return args.size() > PAYLOAD_INDEX ? EJsonInterop.toHostPayload(args.get(PAYLOAD_INDEX), ops) : null;
    }

    private static JsonObject requireObject(JsonBaseElement converted, String message) {
        if (converted instanceof JsonObject object) {
            return object;
        }
        throw new TypeErrorException(message);
    }

    private static JsonArray requireArray(JsonBaseElement converted, String message) {
        if (converted instanceof JsonArray array) {
            return array;
        }
        throw new TypeErrorException(message);
    }
}

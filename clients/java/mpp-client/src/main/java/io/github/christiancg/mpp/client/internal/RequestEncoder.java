package io.github.christiancg.mpp.client.internal;

import io.github.christiancg.mpp.client.Document;
import io.github.christiancg.mpp.client.protocol.Operation;
import io.github.christiancg.mpp.client.query.Filter;
import java.util.List;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public record RequestEncoder(PipelineEncoder pipeline) {
    static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    static final String PROJECT = "projectName";
    static final String COLLECTION = "collectionName";
    static final String STEPS = "aggregationSteps";
    private static final String ID = "_id";

    public RequestEncoder(Json json) {
        this(new PipelineEncoder(json));
    }

    static ObjectNode of(Operation operation) {
        return NODES.objectNode().put("type", operation.name());
    }

    static ObjectNode of(Operation operation, String project) {
        return of(operation).put(PROJECT, project);
    }

    static ObjectNode of(Operation operation, String project, String collection) {
        return of(operation, project).put(COLLECTION, collection);
    }

    public ObjectNode authenticate(String username, String password) {
        return of(Operation.AUTHENTICATE).put("username", username).put("password", password);
    }

    public ObjectNode save(String project, String collection, Document document) {
        return of(Operation.SAVE, project, collection).set("object", Documents.node(document));
    }

    public ObjectNode insert(String project, String collection, Document document) {
        return of(Operation.SAVE, project, collection).put("failOnUpdate", true).set("object",
                Documents.node(document));
    }

    public ObjectNode updateWhere(String project, String collection, Filter condition, Document patch) {
        return of(Operation.SAVE, project, collection).set("condition", pipeline.filter(condition)).set("object",
                Documents.node(patch));
    }

    public ObjectNode bulkSave(String project, String collection, List<Document> documents) {
        final var request = of(Operation.BULK_SAVE, project, collection);
        final var objects = request.putArray("objects");
        documents.forEach(document -> objects.add(Documents.node(document)));
        return request;
    }

    public ObjectNode findById(String project, String collection, String id, boolean dirtyRead) {
        return dirty(of(Operation.FIND_BY_ID, project, collection).put(ID, id), dirtyRead);
    }

    public ObjectNode delete(String project, String collection, String id) {
        return of(Operation.DELETE, project, collection).put(ID, id);
    }

    public ObjectNode deleteWhere(String project, String collection, Filter condition) {
        return of(Operation.DELETE, project, collection).set("condition", pipeline.filter(condition));
    }

    public ObjectNode aggregate(String project, String collection, List<PipelineStep> steps, boolean dirtyRead,
            boolean analyze) {
        final var request = of(Operation.AGGREGATE, project, collection);
        request.set(STEPS, pipeline.steps(steps));
        if (analyze) {
            request.put("analyze", true);
        }
        return dirty(request, dirtyRead);
    }

    public ObjectNode listen(String project, String collection, List<PipelineStep> steps) {
        return of(Operation.LISTEN, project, collection).set(STEPS, pipeline.steps(steps));
    }

    public ObjectNode stopListen(String listenId) {
        return of(Operation.STOP_LISTEN).put("listenId", listenId);
    }

    public ObjectNode listProjects() {
        return of(Operation.LIST_PROJECTS);
    }

    public ObjectNode createProject(String project) {
        return of(Operation.CREATE_PROJECT, project);
    }

    public ObjectNode dropProject(String project) {
        return of(Operation.DROP_PROJECT, project);
    }

    public ObjectNode listCollections(String project) {
        return of(Operation.LIST_COLLECTIONS, project);
    }

    public ObjectNode createCollection(String project, String collection) {
        return of(Operation.CREATE_COLLECTION, project, collection);
    }

    public ObjectNode dropCollection(String project, String collection) {
        return of(Operation.DROP_COLLECTION, project, collection);
    }

    public ObjectNode createIndex(String project, String collection, List<String> fields) {
        final var request = of(Operation.CREATE_INDEX, project, collection);
        if (fields.size() == 1) {
            return request.put("fieldName", fields.get(0));
        }
        final var names = request.putArray("fieldNames");
        fields.forEach(names::add);
        return request;
    }

    public ObjectNode dropIndex(String project, String collection, String field) {
        return of(Operation.DROP_INDEX, project, collection).put("fieldName", field);
    }

    public ObjectNode reindex(String project, String collection, List<String> fields) {
        final var request = of(Operation.REINDEX, project, collection);
        if (!fields.isEmpty()) {
            final var names = request.putArray("fieldNames");
            fields.forEach(names::add);
        }
        return request;
    }

    public ObjectNode reindexPrimaryKey(String project, String collection) {
        return of(Operation.REINDEX, project, collection).put("primaryKey", true);
    }

    public ObjectNode saveSchema(String project, String collection, Document schema) {
        return of(Operation.SAVE_SCHEMA, project, collection).set("schema", Documents.node(schema));
    }

    public ObjectNode getSchema(String project, String collection) {
        return of(Operation.GET_SCHEMA, project, collection);
    }

    public ObjectNode deleteSchema(String project, String collection) {
        return of(Operation.DELETE_SCHEMA, project, collection);
    }

    public ObjectNode startTransaction() {
        return of(Operation.START_TRANSACTION);
    }

    public ObjectNode commitTransaction() {
        return of(Operation.COMMIT_TRANSACTION);
    }

    public ObjectNode rollbackTransaction() {
        return of(Operation.ROLLBACK_TRANSACTION);
    }

    public ObjectNode serverInfo() {
        return of(Operation.SERVER_INFO);
    }

    public ObjectNode projectStats() {
        return of(Operation.GET_PROJECT_STATS);
    }

    public ObjectNode projectStats(String project) {
        return of(Operation.GET_PROJECT_STATS, project);
    }

    public ObjectNode closeConnection() {
        return of(Operation.CLOSE_CONNECTION);
    }

    public ObjectNode setProjectOwners(String project, List<String> owners) {
        final var request = of(Operation.SET_PROJECT_OWNERS, project);
        final var array = request.putArray("owners");
        owners.forEach(array::add);
        return request;
    }

    private static ObjectNode dirty(ObjectNode request, boolean dirtyRead) {
        return dirtyRead ? request.put("dirtyRead", true) : request;
    }
}

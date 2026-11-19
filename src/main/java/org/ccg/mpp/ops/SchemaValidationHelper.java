package org.ccg.mpp.ops;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.exceptions.InvalidSchemaException;
import org.ccg.mpp.ejson.exceptions.SchemaBudgetExceededException;
import org.ccg.mpp.ejson.validate.SchemaBudget;
import org.ccg.mpp.ejson.validate.SchemaValidationResult;
import org.ccg.mpp.ex.MetadataReadException;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.resp.OperationResponse;

public final class SchemaValidationHelper {
    private static final Logger logger = Logger.logFor(SchemaValidationHelper.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Map<String, CheckedSchema> checkedSchemas = new ConcurrentHashMap<>();

    private record CheckedSchema(WeakReference<JsonObject> source, SchemaValidationResult metaResult) {
    }

    private SchemaValidationHelper() {
    }

    public static OperationResponse check(OperationRequest request) {
        try {
            return switch (request.getType()) {
                case SAVE -> checkSave((SaveRequest) request);
                case BULK_SAVE -> checkBulkSave((BulkSaveRequest) request);
                default -> null;
            };
        } catch (MetadataReadException e) {
            logger.error("Refusing the write: cannot read the schema for " + request.getProjectName() + "|"
                    + request.getCollectionName(), e);
            return new OperationResponse(request.getType(), ErrorCode.SCHEMA_UNAVAILABLE);
        } catch (InvalidSchemaException e) {
            logger.error("Refusing the write: the stored schema for " + request.getProjectName() + "|"
                    + request.getCollectionName() + " does not meta-validate", e);
            return new OperationResponse(request.getType(), ErrorCode.SCHEMA_UNAVAILABLE);
        } catch (SchemaBudgetExceededException e) {
            logger.warning("Refusing the write: validating it against the schema of " + request.getProjectName() + "|"
                    + request.getCollectionName() + " exceeded the schema validation budget");
            return new OperationResponse(request.getType(), ErrorCode.SCHEMA_VALIDATION_BUDGET_EXCEEDED);
        } catch (Exception e) {
            logger.error("Refusing the write: the schema check for " + request.getProjectName() + "|"
                    + request.getCollectionName() + " failed unexpectedly", e);
            return new OperationResponse(request.getType(), ErrorCode.SCHEMA_UNAVAILABLE);
        }
    }

    private static OperationResponse checkSave(SaveRequest request) {
        if (request.getCondition() != null) {
            return null;
        }
        final var schema = governingSchema(request.getProjectName(), request.getCollectionName());
        final var document = request.getObject();
        if (schema == null || alreadyChecked(request, document, schema)) {
            return null;
        }
        final var budget = newBudget();
        final var errors = errorsAgainst(schema, document, budget);
        if (errors == null) {
            rememberChecked(request, document, schema, budget);
            return null;
        }
        return new OperationResponse(OperationType.SAVE,
                ErrorCode.SCHEMA_VALIDATION_FAILED.getDefaultMessage() + ": " + errors,
                ErrorCode.SCHEMA_VALIDATION_FAILED);
    }

    private static OperationResponse checkBulkSave(BulkSaveRequest request) {
        final var schema = governingSchema(request.getProjectName(), request.getCollectionName());
        final var documents = request.getObjects();
        if (schema == null || alreadyChecked(request, documents, schema)) {
            return null;
        }
        final var budget = newBudget();
        for (final var object : documents) {
            final var errors = errorsAgainst(schema, object, budget);
            if (errors != null) {
                return new OperationResponse(
                        OperationType.BULK_SAVE, ErrorCode.SCHEMA_VALIDATION_FAILED.getDefaultMessage()
                                + " for document '" + idOf(object) + "': " + errors,
                        ErrorCode.SCHEMA_VALIDATION_FAILED);
            }
        }
        rememberChecked(request, documents, schema, budget);
        return null;
    }

    // Also re-validates a before-hook's replacement document: the checks above run at the edge, before the hook.
    public static String schemaErrors(String projectName, String collName, JsonObject object, SchemaBudget budget) {
        final var schema = governingSchema(projectName, collName);
        return schema == null ? null : errorsAgainst(schema, object, budget);
    }

    public static SchemaBudget budgetFor(OperationRequest request) {
        final var checked = request.getSchemaCheck();
        return checked != null ? checked.budget() : newBudget();
    }

    private static JsonObject governingSchema(String projectName, String collName) {
        final var schema = cache.getCollectionSchema(projectName, collName);
        if (schema != null) {
            requireValidSchema(projectName, collName, schema);
        }
        return schema;
    }

    public static SchemaBudget newBudget() {
        return new SchemaBudget(configuration.getSchemaValidationBudget());
    }

    private static String errorsAgainst(JsonObject schema, JsonObject object, SchemaBudget budget) {
        final var result = eJson.validateInstanceWithout(object, Globals.PK_FIELD, schema, budget);
        return result.isValid() ? null : String.join("; ", result.getErrors());
    }

    private static boolean alreadyChecked(OperationRequest request, Object payload, JsonObject schema) {
        final var checked = request.getSchemaCheck();
        return checked != null && checked.payload() == payload && checked.schema() == schema;
    }

    private static void rememberChecked(OperationRequest request, Object payload, JsonObject schema,
            SchemaBudget budget) {
        request.setSchemaCheck(new OperationRequest.SchemaCheck(payload, schema, budget));
    }

    public static void forgetCollection(String projectName, String collName) {
        checkedSchemas.remove(projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName);
    }

    public static void forgetProject(String projectName) {
        final var prefix = projectName + Globals.COLL_IDENTIFIER_SEPARATOR;
        checkedSchemas.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static void requireValidSchema(String projectName, String collName, JsonObject schema) {
        final var key = Cache.getCollectionIdentifier(projectName, collName);
        final var cached = checkedSchemas.get(key);
        final var checked = cached != null && cached.source().get() == schema
                ? cached
                : new CheckedSchema(new WeakReference<>(schema), eJson.validateSchema(schema));
        if (checked != cached) {
            checkedSchemas.put(key, checked);
        }
        if (!checked.metaResult().isValid()) {
            throw new InvalidSchemaException("Cannot validate against an invalid schema: "
                    + String.join("; ", checked.metaResult().getErrors()));
        }
    }

    private static String idOf(JsonObject object) {
        final var id = object.get(Globals.PK_FIELD);
        return id != null && id.isJsonString() ? id.asJsonString().getValue() : "(no _id)";
    }
}

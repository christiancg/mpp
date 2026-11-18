package org.ccg.mpp.ejson;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Set;
import org.ccg.mpp.ejson.custom_types.CustomTypeFactory;
import org.ccg.mpp.ejson.custom_types.JsonDateTime;
import org.ccg.mpp.ejson.custom_types.JsonGeo;
import org.ccg.mpp.ejson.custom_types.JsonTime;
import org.ccg.mpp.ejson.custom_types.JsonVector;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonBoolean;
import org.ccg.mpp.ejson.elements.JsonNull;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonPrimitive;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ejson.exceptions.InvalidSchemaException;
import org.ccg.mpp.ejson.internal.EncodedJson;
import org.ccg.mpp.ejson.internal.JsonPrettyWriter;
import org.ccg.mpp.ejson.internal.JsonReader;
import org.ccg.mpp.ejson.internal.JsonWriter;
import org.ccg.mpp.ejson.type_adapters.TypeAdapterFactory;
import org.ccg.mpp.ejson.type_adapters.impl.BooleanTypeAdapter;
import org.ccg.mpp.ejson.type_adapters.impl.JsonArrayTypeAdapter;
import org.ccg.mpp.ejson.type_adapters.impl.JsonBaseElementTypeAdapter;
import org.ccg.mpp.ejson.type_adapters.impl.JsonObjectTypeAdapter;
import org.ccg.mpp.ejson.type_adapters.impl.JsonPrimitiveTypeAdapter;
import org.ccg.mpp.ejson.type_adapters.impl.NumberTypeAdapter;
import org.ccg.mpp.ejson.type_adapters.impl.StringTypeAdapter;
import org.ccg.mpp.ejson.validate.JsonSchema;
import org.ccg.mpp.ejson.validate.MetaSchemaValidator;
import org.ccg.mpp.ejson.validate.SchemaBudget;
import org.ccg.mpp.ejson.validate.SchemaComplexity;
import org.ccg.mpp.ejson.validate.SchemaPatternLengths;
import org.ccg.mpp.ejson.validate.SchemaValidationResult;
import org.ccg.mpp.ejson.validate.SchemaValidator;

public class EJson {
    private final JsonReader reader = new JsonReader();
    private final JsonWriter writer = new JsonWriter();
    private final MetaSchemaValidator metaSchemaValidator = new MetaSchemaValidator();
    private final SchemaValidator schemaValidator = new SchemaValidator();

    public EJson() {
        registerTypeAdapters();
        registerExtendedTypes();
    }

    private void registerTypeAdapters() {
        registerBooleanAdapters();
        registerNumberAdapters();
        registerStringAdapters();
        registerEjsonElementAdapters();
    }

    private void registerBooleanAdapters() {
        final var booleanTypeAdapter = new BooleanTypeAdapter();
        TypeAdapterFactory.registerTypeAdapter(Boolean.class, booleanTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(boolean.class, booleanTypeAdapter);
    }

    private void registerNumberAdapters() {
        final var numberTypeAdapter = new NumberTypeAdapter();
        TypeAdapterFactory.registerTypeAdapter(Number.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(short.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(Short.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(int.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(Integer.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(long.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(Long.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(BigInteger.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(float.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(Float.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(double.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(Double.class, numberTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(BigDecimal.class, numberTypeAdapter);
    }

    private void registerStringAdapters() {
        final var stringTypeAdapter = new StringTypeAdapter();
        TypeAdapterFactory.registerTypeAdapter(String.class, stringTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(StringBuilder.class, stringTypeAdapter);
    }

    private void registerEjsonElementAdapters() {
        final var jsonBaseElementTypeAdapter = new JsonBaseElementTypeAdapter();
        TypeAdapterFactory.registerTypeAdapter(JsonBoolean.class, jsonBaseElementTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(JsonNumber.class, jsonBaseElementTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(JsonNull.class, jsonBaseElementTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(JsonString.class, jsonBaseElementTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(JsonBaseElement.class, jsonBaseElementTypeAdapter);
        TypeAdapterFactory.registerTypeAdapter(JsonPrimitive.class, new JsonPrimitiveTypeAdapter());
        TypeAdapterFactory.registerTypeAdapter(JsonObject.class, new JsonObjectTypeAdapter());
        TypeAdapterFactory.registerTypeAdapter(JsonArray.class, new JsonArrayTypeAdapter());
    }

    private void registerExtendedTypes() {
        CustomTypeFactory.registerCustomType(JsonTime.class);
        CustomTypeFactory.registerCustomType(JsonDateTime.class);
        CustomTypeFactory.registerCustomType(JsonGeo.class);
        CustomTypeFactory.registerCustomType(JsonVector.class);
    }

    public <T> T fromJson(String jsonString, Class<T> tClass) {
        return reader.fromJson(jsonString, tClass);
    }

    public <T> T fromJson(String jsonString, Class<T> tClass, int maxDepth) {
        return reader.fromJson(jsonString, tClass, maxDepth);
    }

    public <T> T fromJson(String jsonString, Class<T> tClass, int maxDepth, int maxTokens) {
        return reader.fromJson(jsonString, tClass, maxDepth, maxTokens);
    }

    public <T> T fromJson(byte[] utf8, int start, int end, Class<T> tClass) {
        return reader.fromJson(utf8, start, end, tClass, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public <T> T fromJson(byte[] utf8, int start, int end, Class<T> tClass, int maxDepth, int maxTokens) {
        return reader.fromJson(utf8, start, end, tClass, maxDepth, maxTokens);
    }

    public <T> T fromJson(JsonBaseElement jsonObject, Class<T> tClass) {
        final var adapter = TypeAdapterFactory.getAdapter(tClass);
        return adapter.fromJson(jsonObject);
    }

    public <T> T fromJson(JsonObject jsonObject, Class<T> tClass, Set<String> skippedKeys) {
        return TypeAdapterFactory.getAdapter(tClass).fromJson(jsonObject, skippedKeys);
    }

    @SuppressWarnings("unchecked")
    public <T> String toJson(T obj) {
        final Class<T> clazz = (Class<T>) obj.getClass();
        return writer.toJson(obj, clazz);
    }

    @SuppressWarnings("unchecked")
    public <T> byte[] toJsonBytes(T obj, int sizeHint) {
        final Class<T> clazz = (Class<T>) obj.getClass();
        return writer.toJsonBytes(obj, clazz, sizeHint);
    }

    @SuppressWarnings("unchecked")
    public <T> EncodedJson toJsonFrame(T obj, int sizeHint) {
        final Class<T> clazz = (Class<T>) obj.getClass();
        return writer.toJsonFrame(obj, clazz, sizeHint);
    }

    @SuppressWarnings("unchecked")
    public <T> byte[] toJsonLine(T obj, int sizeHint, String terminator) {
        final Class<T> clazz = (Class<T>) obj.getClass();
        return writer.toJsonLine(obj, clazz, sizeHint, terminator);
    }

    public String toJson(JsonBaseElement element, String indent) {
        if (element == null) {
            return "null";
        }
        if (indent == null || indent.isEmpty()) {
            return toJson(element);
        }
        return JsonPrettyWriter.toJson(element, indent);
    }

    public SchemaValidationResult validateSchema(JsonObject schema) {
        return metaSchemaValidator.validate(schema);
    }

    public SchemaValidationResult validateSchema(String schemaJson) {
        final JsonObject parsed;
        try {
            parsed = reader.fromJson(schemaJson, JsonObject.class);
        } catch (RuntimeException e) {
            return SchemaValidationResult.invalid("Schema is not a valid JSON object: " + e.getMessage());
        }
        return metaSchemaValidator.validate(parsed);
    }

    public SchemaValidationResult validateWithSchema(JsonBaseElement instance, JsonObject schema) {
        final var schemaResult = metaSchemaValidator.validate(schema);
        if (!schemaResult.isValid()) {
            throw new InvalidSchemaException(
                    "Cannot validate against an invalid schema: " + String.join("; ", schemaResult.getErrors()));
        }
        return schemaValidator.validate(instance, new JsonSchema(schema));
    }

    public SchemaValidationResult validateInstance(JsonBaseElement instance, JsonObject schema) {
        return schemaValidator.validate(instance, new JsonSchema(schema));
    }

    public SchemaValidationResult validateInstance(JsonBaseElement instance, JsonObject schema, long stepBudget) {
        return schemaValidator.validate(instance, new JsonSchema(schema, stepBudget));
    }

    public SchemaValidationResult validateInstance(JsonBaseElement instance, JsonObject schema,
            SchemaBudget sharedBudget) {
        return schemaValidator.validate(instance, new JsonSchema(schema, sharedBudget));
    }

    public SchemaValidationResult validateInstanceWithout(JsonObject instance, String hiddenProperty, JsonObject schema,
            SchemaBudget sharedBudget) {
        return schemaValidator.validate(instance, new JsonSchema(schema, sharedBudget, instance, hiddenProperty));
    }

    public long schemaExpandedNodeCount(JsonObject schema, int limit) {
        return SchemaComplexity.expandedNodeCount(schema, limit);
    }

    public int longestSchemaPattern(JsonObject schema) {
        return SchemaPatternLengths.longestPattern(schema);
    }

    public int schemaPatternCount(JsonObject schema) {
        return SchemaPatternLengths.count(schema);
    }
}

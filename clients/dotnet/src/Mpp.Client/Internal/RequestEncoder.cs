using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using System.Text.Json.Nodes;
using Mpp.Client.Query;

namespace Mpp.Client.Internal;

internal sealed class RequestEncoder(TimeZoneInfo? temporalZone)
{
    public PipelineEncoder Pipeline { get; } = new(temporalZone);

    public TimeZoneInfo? TemporalZone { get; } = temporalZone;

    public static JsonObject Request(string type, string? project = null, string? collection = null)
    {
        var frame = new JsonObject { ["type"] = type };
        if (project is not null)
        {
            frame["projectName"] = project;
        }
        if (collection is not null)
        {
            frame["collectionName"] = collection;
        }
        return frame;
    }

    public static JsonArray Strings(IEnumerable<string> values) => [.. values.Select(value => (JsonNode)value)];

    public static JsonObject Document(Document document) => (JsonObject)document.Node.DeepClone();

    public static JsonObject Authenticate(string username, string password)
    {
        var frame = Request("AUTHENTICATE");
        frame["username"] = username;
        frame["password"] = password;
        return frame;
    }

    public static JsonObject Save(string project, string collection, JsonObject document) =>
        With(Request("SAVE", project, collection), "object", document);

    public static JsonObject Insert(string project, string collection, JsonObject document) =>
        With(With(Request("SAVE", project, collection), "failOnUpdate", true), "object", document);

    public JsonObject SaveWhere(string project, string collection, Filter condition, JsonObject patch) =>
        With(With(Request("SAVE", project, collection), "condition", Pipeline.Filter(condition)), "object", patch);

    public static JsonObject BulkSave(string project, string collection, IEnumerable<JsonObject> documents) =>
        With(Request("BULK_SAVE", project, collection), "objects", new JsonArray([.. documents]));

    public static JsonObject FindById(string project, string collection, string id, bool dirtyRead) =>
        Dirty(With(Request("FIND_BY_ID", project, collection), "_id", id), dirtyRead);

    public static JsonObject Delete(string project, string collection, string id) =>
        With(Request("DELETE", project, collection), "_id", id);

    public JsonObject DeleteWhere(string project, string collection, Filter condition) =>
        With(Request("DELETE", project, collection), "condition", Pipeline.Filter(condition));

    public JsonObject Aggregate(string project, string collection, ImmutableArray<PipelineStep> steps, bool dirtyRead, bool analyze)
    {
        var frame = With(Request("AGGREGATE", project, collection), "aggregationSteps", Pipeline.Steps(steps));
        if (analyze)
        {
            frame["analyze"] = true;
        }
        return Dirty(frame, dirtyRead);
    }

    public JsonObject Listen(string project, string collection, ImmutableArray<PipelineStep> steps) =>
        With(Request("LISTEN", project, collection), "aggregationSteps", Pipeline.Steps(steps));

    public static JsonObject StopListen(string listenId) => With(Request("STOP_LISTEN"), "listenId", listenId);

    public static JsonObject CreateIndex(string project, string collection, IReadOnlyList<string> fields)
    {
        var frame = Request("CREATE_INDEX", project, collection);
        return fields.Count == 1 ? With(frame, "fieldName", fields[0]) : With(frame, "fieldNames", Strings(fields));
    }

    public static JsonObject DropIndex(string project, string collection, string field) =>
        With(Request("DROP_INDEX", project, collection), "fieldName", field);

    public static JsonObject Reindex(string project, string collection, IReadOnlyList<string> fields)
    {
        var frame = Request("REINDEX", project, collection);
        return fields.Count == 0 ? frame : With(frame, "fieldNames", Strings(fields));
    }

    public static JsonObject ReindexPrimaryKey(string project, string collection) =>
        With(Request("REINDEX", project, collection), "primaryKey", true);

    public static JsonObject SaveSchema(string project, string collection, JsonObject schema) =>
        With(Request("SAVE_SCHEMA", project, collection), "schema", schema);

    public static JsonObject SetProjectOwners(string project, IEnumerable<string> owners) =>
        With(Request("SET_PROJECT_OWNERS", project), "owners", Strings(owners));

    public static JsonObject With(JsonObject frame, string field, JsonNode? value)
    {
        frame[field] = value;
        return frame;
    }

    private static JsonObject Dirty(JsonObject frame, bool dirtyRead) => dirtyRead ? With(frame, "dirtyRead", true) : frame;
}

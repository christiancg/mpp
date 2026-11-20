using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using System.Text.Json.Nodes;
using Mpp.Client.Admin;
using Mpp.Client.Query;
using Mpp.Client.Scripting;
using static Mpp.Client.Internal.RequestEncoder;

namespace Mpp.Client.Internal;

internal sealed class ScriptRequestEncoder(RequestEncoder encoder)
{
    public static JsonObject RunScript(string project, string script, Document? args) =>
        Args(With(Request("RUN_SCRIPT", project), "script", script), args);

    public static JsonObject SaveProcedure(string project, ProcedureDefinition procedure)
    {
        var frame = Request("SAVE_PROCEDURE", project);
        frame["name"] = procedure.Name;
        frame["script"] = procedure.Script;
        Optional(frame, "description", procedure.Description);
        Optional(frame, "enabled", procedure.Enabled);
        Optional(frame, "ifVersion", procedure.IfVersion);
        return frame;
    }

    public static JsonObject DeleteProcedure(string project, string name) =>
        With(Request("DELETE_PROCEDURE", project), "name", name);

    public static JsonObject ListProcedures(string project, bool? includeSource) =>
        Optional(Request("LIST_PROCEDURES", project), "includeSource", includeSource);

    public static JsonObject CallProcedure(string project, string name, Document? args) =>
        Args(With(Request("CALL_PROCEDURE", project), "procedureName", name), args);

    public static JsonObject SaveTrigger(string project, string collection, TriggerDefinition trigger)
    {
        if (trigger.Events.Count == 0)
        {
            throw new ArgumentException("a trigger needs at least one event", nameof(trigger));
        }
        var frame = Request("SAVE_TRIGGER", project, collection);
        frame["name"] = trigger.Name;
        frame["events"] = Strings(trigger.Events.Select(triggerEvent => WireNames.Upper(triggerEvent.ToString())));
        frame["procedureName"] = trigger.ProcedureName;
        Optional(frame, "timing", Lower(trigger.Timing));
        Optional(frame, "mode", Lower(trigger.Mode));
        Optional(frame, "allowCascade", trigger.AllowCascade);
        Optional(frame, "enabled", trigger.Enabled);
        Optional(frame, "ifVersion", trigger.IfVersion);
        return frame;
    }

    public static JsonObject DeleteTrigger(string project, string collection, string name) =>
        With(Request("DELETE_TRIGGER", project, collection), "name", name);

    public static JsonObject ListTriggers(string project, string? collection) => Request("LIST_TRIGGERS", project, collection);

    public static JsonObject TestTrigger(string project, string collection, string name, TriggerEvent triggerEvent, Document document)
    {
        var frame = Request("TEST_TRIGGER", project, collection);
        frame["name"] = name;
        frame["event"] = WireNames.Upper(triggerEvent.ToString());
        frame["document"] = Document(document);
        return frame;
    }

    public static JsonObject SaveSchedule(string project, ScheduleDefinition schedule)
    {
        if (schedule.Cron is null == schedule.Interval is null)
        {
            throw new ArgumentException("a schedule takes exactly one of Cron and Interval", nameof(schedule));
        }
        var frame = Request("SAVE_SCHEDULE", project);
        frame["name"] = schedule.Name;
        frame["procedureName"] = schedule.ProcedureName;
        Optional(frame, "cron", schedule.Cron);
        Optional(frame, "intervalMs", Millis(schedule.Interval));
        Args(frame, schedule.Args);
        Optional(frame, "timeoutMs", Millis(schedule.Timeout));
        Optional(frame, "description", schedule.Description);
        Optional(frame, "enabled", schedule.Enabled);
        Optional(frame, "ifVersion", schedule.IfVersion);
        return frame;
    }

    public static JsonObject DeleteSchedule(string project, string name) =>
        With(Request("DELETE_SCHEDULE", project), "name", name);

    public static JsonObject ListSchedules(string project, bool? includeArgs) =>
        Optional(Request("LIST_SCHEDULES", project), "includeArgs", includeArgs);

    public static JsonObject CancelScript(string runId) => With(Request("CANCEL_SCRIPT"), "runId", runId);

    public static JsonObject ListTriggerRuns(TriggerRunStatus? status) =>
        Optional(Request("LIST_TRIGGER_RUNS"), "status", status is null ? null : WireNames.Upper(status.ToString()!));

    public static JsonObject ResolveTriggerRun(string runId, TriggerRunDecision decision) =>
        With(With(Request("RESOLVE_TRIGGER_RUN"), "runId", runId), "decision", Lower(decision));

    public static JsonObject ResolveTransaction(string dtxId, TransactionDecision decision) =>
        With(With(Request("RESOLVE_TRANSACTION"), "dtxId", dtxId), "decision", Lower(decision));

    public static JsonObject CreateUser(string username, string password, Permissions? granted)
    {
        var frame = Request("CREATE_USER");
        frame["username"] = username;
        frame["password"] = password;
        return granted is null ? frame : Grant(frame, granted);
    }

    public static JsonObject DeleteUser(string username) => With(Request("DELETE_USER"), "username", username);

    public static JsonObject ChangePermissions(string username, Permissions granted) =>
        Grant(With(Request("CHANGE_PERMISSIONS"), "username", username), granted);

    public static JsonObject SetPassword(string username, string? currentPassword, string newPassword)
    {
        var frame = With(Request("SET_PASSWORD"), "username", username);
        Optional(frame, "currentPassword", currentPassword);
        frame["newPassword"] = newPassword;
        return frame;
    }

    public JsonObject ListUsers(ImmutableArray<PipelineStep> steps) =>
        steps.IsEmpty ? Request("LIST_USERS") : With(Request("LIST_USERS"), "aggregationSteps", encoder.Pipeline.Steps(steps));

    private static JsonObject Grant(JsonObject frame, Permissions granted)
    {
        frame["admin"] = granted.Admin;
        frame["globalPermissions"] = Strings(granted.Global.Select(permission => WireNames.Upper(permission.ToString())));
        frame["projectPermissions"] = Grants(granted.Projects);
        frame["collectionPermissions"] = Grants(granted.Collections);
        frame["scriptPermissions"] = Grants(granted.Scripts);
        return frame;
    }

    private static JsonObject Grants<TPermission>(IReadOnlyDictionary<string, TPermission> grants)
        where TPermission : struct, Enum
    {
        var encoded = new JsonObject();
        foreach (var (key, permission) in grants)
        {
            encoded[key] = WireNames.Upper(permission.ToString());
        }
        return encoded;
    }

    private static JsonObject Args(JsonObject frame, Document? args) => args is null ? frame : With(frame, "args", Document(args));

    private static JsonObject Optional(JsonObject frame, string field, JsonNode? value) =>
        value is null ? frame : With(frame, field, value);

    private static long? Millis(TimeSpan? duration) => duration is { } value ? (long)value.TotalMilliseconds : null;

    private static string? Lower<TEnum>(TEnum? value)
        where TEnum : struct, Enum =>
        value?.ToString().ToLowerInvariant();

    private static string Lower<TEnum>(TEnum value)
        where TEnum : struct, Enum =>
        value.ToString().ToLowerInvariant();
}

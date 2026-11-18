using System;
using System.Text.Json;
using System.Text.Json.Serialization;
using Mpp.Client.Errors;
using Mpp.Client.Internal;

namespace Mpp.Client.Mapping;

internal sealed class DateTimeConverter(TimeZoneInfo? zone) : JsonConverter<DateTime>
{
    public override DateTime Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
        CustomReading.Read<DateTime>(ref reader, "a date-time");

    public override void Write(Utf8JsonWriter writer, DateTime value, JsonSerializerOptions options)
    {
        try
        {
            writer.WriteStringValue(CustomValues.Format(DocumentValues.WallClock(value, string.Empty, zone)));
        }
        catch (MappingException error)
        {
            throw new JsonException(error.Message, error);
        }
    }
}

internal sealed class DateTimeOffsetConverter(TimeZoneInfo? zone) : JsonConverter<DateTimeOffset>
{
    public override DateTimeOffset Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        var wallClock = CustomReading.Read<DateTime>(ref reader, "a date-time");
        return zone is not null
            ? new DateTimeOffset(wallClock, zone.GetUtcOffset(wallClock))
            : throw new JsonException("a DateTimeOffset needs ClientSettings.TemporalZone, since the server's date-time has no zone");
    }

    public override void Write(Utf8JsonWriter writer, DateTimeOffset value, JsonSerializerOptions options)
    {
        try
        {
            writer.WriteStringValue(CustomValues.Format(DocumentValues.WallClock(value, string.Empty, zone)));
        }
        catch (MappingException error)
        {
            throw new JsonException(error.Message, error);
        }
    }
}

internal static class CustomReading
{
    public static T Read<T>(ref Utf8JsonReader reader, string label)
    {
        if (reader.TokenType != JsonTokenType.String)
        {
            throw new JsonException($"expected {label}, found {reader.TokenType}");
        }
        try
        {
            return CustomValues.ParseAs<T>(reader.GetString()!, label);
        }
        catch (MappingException error)
        {
            throw new JsonException(error.Message, error);
        }
    }
}

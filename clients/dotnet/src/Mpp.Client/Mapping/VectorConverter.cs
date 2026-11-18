using System;
using System.Text.Json;
using System.Text.Json.Serialization;
using Mpp.Client.Internal;
using Mpp.Client.Values;

namespace Mpp.Client.Mapping;

internal sealed class VectorConverter : JsonConverter<Vector>
{
    public override Vector Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
        CustomReading.Read<Vector>(ref reader, "a vector");

    public override void Write(Utf8JsonWriter writer, Vector value, JsonSerializerOptions options) =>
        writer.WriteStringValue(CustomValues.Format(value));
}

using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Linq.Expressions;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Mpp.Client.Internal.Linq;

internal static class ResultShaper
{
    private const string CountField = "count";
    private const string GroupField = "group";
    private const string IndexParameter = "index";

    public static object? Shape(QueryModel model, IReadOnlyList<Document> rows, Type resultType, JsonSerializerOptions options) =>
        model.Terminal switch
        {
            Terminal.Count => (int)CountOf(rows),
            Terminal.LongCount => CountOf(rows),
            Terminal.Any => rows.Count > 0,
            Terminal.All => rows.Count == 0,
            _ => ShapeElements(model, rows, resultType, options),
        };

    private static object? ShapeElements(QueryModel model, IReadOnlyList<Document> rows, Type resultType, JsonSerializerOptions options)
    {
        var elements = Elements(model, rows, options);
        return model.Terminal switch
        {
            Terminal.Sequence => elements,
            Terminal.First => elements.Count > 0 ? elements[0] : throw new InvalidOperationException("Sequence contains no elements"),
            Terminal.Single => Single(elements) ?? throw new InvalidOperationException("Sequence contains no elements"),
            Terminal.SingleOrDefault => elements.Count == 0 ? Default(resultType) : Single(elements),
            Terminal.ElementAt => elements.Count > 0
                ? elements[0]
                : throw PastTheEnd(),
            _ => elements.Count > 0 ? elements[0] : Default(resultType),
        };
    }

    private static ArgumentOutOfRangeException PastTheEnd() =>
        new(IndexParameter, "the index is past the end of the results");

    private static long CountOf(IReadOnlyList<Document> rows) => rows.Count == 0 ? 0 : rows[0].GetLong(CountField) ?? 0;

    private static object? Single(IList elements) =>
        elements.Count > 1 ? throw new InvalidOperationException("Sequence contains more than one element") : elements.Count == 1 ? elements[0] : null;

    private static object? Default(Type type) => type.IsValueType ? Activator.CreateInstance(type) : null;

    private static IList Elements(QueryModel model, IReadOnlyList<Document> rows, JsonSerializerOptions options)
    {
        var projections = model.Projections.Select(projection => projection.Compile()).ToList();
        var elements = (IList)Activator.CreateInstance(typeof(List<>).MakeGenericType(model.ResultElementType))!;
        var groupJoin = model.Shape is GroupJoinShape join ? join.ResultSelector.Compile() : null;
        foreach (var row in rows)
        {
            var element = projections.Aggregate(
                Element(row, model.Shape, groupJoin, options),
                (current, projection) => projection.DynamicInvoke(current));
            elements.Add(element);
        }
        return elements;
    }

    private static object? Element(Document row, RowShape shape, Delegate? groupJoin, JsonSerializerOptions options) => shape switch
    {
        EntityShape entity => entity.Root.Decode(row),
        GroupingShape grouping => Group(row, grouping, options),
        ValueShape value => ConstantEncoder.Decode(At(row.Node, value.Value.Path), value.Value, options),
        GroupJoinShape join => groupJoin!.DynamicInvoke(join.Outer.Root.Decode(row), Joined(row, join)),
        _ => throw new InvalidOperationException($"unknown row shape {shape.GetType().Name}"),
    };

    private static object Group(Document row, GroupingShape grouping, JsonSerializerOptions options)
    {
        var key = ConstantEncoder.Decode(At(row.Node, grouping.Key.Path), grouping.Key, options);
        var elements = Decoded(row.Node[GroupField], grouping.Element);
        return Activator.CreateInstance(
            typeof(Grouping<,>).MakeGenericType(grouping.KeyType, grouping.Element.ElementType),
            ConvertKey(key, grouping.KeyType),
            elements)!;
    }

    private static object? ConvertKey(object? key, Type keyType) =>
        key is null || keyType.IsInstanceOfType(key)
            ? key
            : Expression.Lambda(Expression.Convert(Expression.Constant(key), keyType)).Compile().DynamicInvoke();

    private static IList Joined(Document row, GroupJoinShape join) => Decoded(row.Node[join.AsField], join.Inner);

    private static IList Decoded(JsonNode? node, EntityShape shape)
    {
        var list = (IList)Activator.CreateInstance(typeof(List<>).MakeGenericType(shape.ElementType))!;
        if (node is not JsonArray array)
        {
            return list;
        }
        foreach (var item in array.OfType<JsonObject>())
        {
            list.Add(shape.Root.Decode(Document.Wrap(item)));
        }
        return list;
    }

    private static JsonNode? At(JsonObject row, string path) =>
        path.Split('.').Aggregate<string, JsonNode?>(row, (current, segment) => current is JsonObject obj ? obj[segment] : null);
}

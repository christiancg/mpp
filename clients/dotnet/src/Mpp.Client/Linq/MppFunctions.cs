using System;
using Mpp.Client.Values;

namespace Mpp.Client.Linq;

public static class MppFunctions
{
    public static double Distance(GeoPoint field, GeoPoint target) => throw OnlyInQueries(nameof(Distance));

    public static bool Within(GeoPoint field, params GeoPoint[] polygon) => throw OnlyInQueries(nameof(Within));

    public static bool Script(string source) => throw OnlyInQueries(nameof(Script));

    private static InvalidOperationException OnlyInQueries(string name) =>
        new($"MppFunctions.{name} only has a meaning inside an MPP LINQ query, which the server evaluates");
}

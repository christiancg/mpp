using System;
using System.Collections.Generic;
using Mpp.Client.Values;

namespace Mpp.Client.IntegrationTests.Support;

public sealed class Item
{
    public string? Id { get; set; }

    public string? Status { get; set; }

    public double Total { get; set; }

    public double Qty { get; set; }

    public string? Name { get; set; }

    public string? CustomerId { get; set; }

    public List<string> Tags { get; set; } = [];

    public double? Discount { get; set; }

    public bool Paid { get; set; }

    public GeoPoint Location { get; set; }

    public Vector? Embedding { get; set; }
}

public sealed class Customer
{
    public string? Id { get; set; }

    public string? City { get; set; }
}

public sealed class Place
{
    public string? Id { get; set; }

    public DateTime OpenedAt { get; set; }

    public TimeOnly OpensAt { get; set; }

    public GeoPoint Location { get; set; }

    public Vector? Embedding { get; set; }

    [MppVector]
    public float[]? Floats { get; set; }
}

package io.github.christiancg.mpp.client.query;

public sealed interface Filter
        permits FieldFilter, ConjunctionFilter, DistanceFilter, WithinFilter, NearestFilter, ScriptFilter {
}

package io.github.christiancg.mpp.client.query;

import io.github.christiancg.mpp.client.values.GeoDistanceComparator;
import io.github.christiancg.mpp.client.values.GeoPoint;
import java.util.Objects;

public record DistanceFilter(String field, GeoPoint target, GeoDistanceComparator comparator,
        double metres) implements Filter {
    public DistanceFilter {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(comparator, "comparator");
        if (!Double.isFinite(metres) || metres < 0) {
            throw new IllegalArgumentException("distance must be a finite, non-negative number of metres: " + metres);
        }
    }
}

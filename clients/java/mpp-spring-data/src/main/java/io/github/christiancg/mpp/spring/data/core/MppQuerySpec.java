package io.github.christiancg.mpp.spring.data.core;

import io.github.christiancg.mpp.client.query.Filter;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.data.domain.Sort;

public record MppQuerySpec(Optional<Filter> filter, Sort sort, long skip, OptionalInt limit) {
    public MppQuerySpec {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(sort, "sort");
        Objects.requireNonNull(limit, "limit");
        if (skip < 0) {
            throw new IllegalArgumentException("skip must not be negative: " + skip);
        }
    }

    public static MppQuerySpec all() {
        return new MppQuerySpec(Optional.empty(), Sort.unsorted(), 0, OptionalInt.empty());
    }

    public static MppQuerySpec of(Filter filter) {
        return new MppQuerySpec(Optional.of(filter), Sort.unsorted(), 0, OptionalInt.empty());
    }

    public MppQuerySpec sorted(Sort order) {
        return new MppQuerySpec(filter, sort.and(order), skip, limit);
    }

    public MppQuerySpec page(long offset, int size) {
        return new MppQuerySpec(filter, sort, offset, OptionalInt.of(size));
    }

    public MppQuerySpec limited(int maxResults) {
        final var narrower = limit.isPresent() ? Math.min(limit.getAsInt(), maxResults) : maxResults;
        return new MppQuerySpec(filter, sort, skip, OptionalInt.of(narrower));
    }
}

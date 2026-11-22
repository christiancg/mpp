package org.ccg.mpp.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QueryString {
    private QueryString() {
    }

    public static Map<String, List<String>> parse(String rawQuery) {
        final var parameters = new LinkedHashMap<String, List<String>>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return parameters;
        }
        for (final var pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            final var equals = pair.indexOf('=');
            final var key = PercentDecoding.decode(equals < 0 ? pair : pair.substring(0, equals), true);
            final var value = equals < 0 ? "" : PercentDecoding.decode(pair.substring(equals + 1), true);
            parameters.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        return parameters;
    }
}

package io.github.christiancg.mpp.client.scripting;

import io.github.christiancg.mpp.client.Document;
import java.util.Optional;
import java.util.OptionalLong;

public record ScheduleInfo(String name, String procedureName, Optional<String> cron, OptionalLong intervalMs,
        boolean enabled, long version, Optional<String> definer, OptionalLong nextRunAt, Document raw) {
}

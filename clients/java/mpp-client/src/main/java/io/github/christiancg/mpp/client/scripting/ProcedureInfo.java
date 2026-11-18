package io.github.christiancg.mpp.client.scripting;

import io.github.christiancg.mpp.client.Document;
import java.util.Optional;

public record ProcedureInfo(String name, long version, boolean enabled, Optional<String> description,
        Optional<String> source, Document raw) {
}

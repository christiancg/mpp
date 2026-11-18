package io.github.christiancg.mpp.client.admin;

import io.github.christiancg.mpp.client.Document;
import java.util.Optional;

public record ServerInfo(Optional<String> state, Optional<String> version, Document raw) {
}

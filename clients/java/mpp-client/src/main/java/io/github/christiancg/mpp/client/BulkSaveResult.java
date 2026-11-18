package io.github.christiancg.mpp.client;

import java.util.List;

public record BulkSaveResult(List<String> inserted, List<String> updated) {
}

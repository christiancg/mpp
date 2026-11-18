package org.ccg.mpp.fs;

import java.util.List;

public record IndexLogChange(String fileKey, List<String> added, List<String> removed) {
}

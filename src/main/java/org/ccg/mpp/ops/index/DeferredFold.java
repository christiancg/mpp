package org.ccg.mpp.ops.index;

import org.ccg.mpp.fs.PendingFold;

public record DeferredFold(String identifier, PendingFold pending) {
}

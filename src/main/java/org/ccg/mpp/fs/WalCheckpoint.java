package org.ccg.mpp.fs;

import java.util.Map;

record WalCheckpoint(long seq, String projectName, String collName, long pkIndexLength, Map<Long, Long> pageLengths) {
}

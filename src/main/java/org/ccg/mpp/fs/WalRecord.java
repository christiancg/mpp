package org.ccg.mpp.fs;

import java.util.List;

record WalRecord(long seq, List<WalChange> changes) {
}

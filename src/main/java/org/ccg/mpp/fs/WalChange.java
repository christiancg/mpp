package org.ccg.mpp.fs;

record WalChange(WalTarget target, long page, long offset, long length, byte[] bytes) {
    long end() {
        return offset + length;
    }
}

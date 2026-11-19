package org.ccg.mpp.listen;

@FunctionalInterface
public interface DirtySink {
    void markDirty(String projectName, String collName);
}

package org.ccg.mpp.fs;

public interface CollectionTryLock {
    boolean tryLock(String projectName, String collName);

    void unlock(String projectName, String collName);
}

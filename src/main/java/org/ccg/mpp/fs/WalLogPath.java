package org.ccg.mpp.fs;

import java.io.File;
import java.util.Objects;

record WalLogPath(String root, String projectName, String collName, File file, String key) {
    boolean names(String otherRoot, String otherProject, String otherColl) {
        return projectName.equals(otherProject) && collName.equals(otherColl) && Objects.equals(root, otherRoot);
    }
}

package org.ccg.mpp.ex;

import java.io.IOException;

public class ProjectFilesBusyException extends IOException {
    public ProjectFilesBusyException(String projectName, String reason) {
        super("The files of project '" + projectName + "' are not available right now: " + reason);
    }
}

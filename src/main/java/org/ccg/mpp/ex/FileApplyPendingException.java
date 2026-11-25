package org.ccg.mpp.ex;

import java.io.IOException;

public class FileApplyPendingException extends IOException {
    public FileApplyPendingException(String projectName, IOException failure) {
        super("A change to the files of project '" + projectName + "' was committed but could not be applied yet; it"
                + " is applied on the next access to the project's files, or at the next startup", failure);
    }
}

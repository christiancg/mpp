package org.ccg.mpp.ops.admin;

import java.io.IOException;
import org.ccg.mpp.fs.ProjectFileStore;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;

public final class ProjectFilesRetirement {
    private static final ProjectFileStore store = IocContainer.get(ProjectFileStore.class);
    private static final Logger logger = Logger.logFor(ProjectFilesRetirement.class);

    private ProjectFilesRetirement() {
    }

    public static void drop(String projectName) {
        try {
            store.dropProject(projectName);
        } catch (IOException | RuntimeException e) {
            logger.error("The files of dropped project '" + projectName + "' could not be deleted; a project created"
                    + " later under the same name moves them aside instead of using them", e);
        }
    }

    public static void quarantine(String projectName, String reason) {
        try {
            if (store.holdsFiles(projectName)) {
                store.quarantine(projectName, reason);
            }
        } catch (IOException | RuntimeException e) {
            logger.error("The files of project '" + projectName + "' could not be moved aside", e);
        }
    }
}

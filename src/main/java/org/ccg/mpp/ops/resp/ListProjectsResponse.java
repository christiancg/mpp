package org.ccg.mpp.ops.resp;

import java.util.List;
import org.ccg.mpp.ejson.internal.JsonSizeEstimator;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ListProjectsResponse extends OperationResponse {
    private final List<String> projects;

    public ListProjectsResponse(String message, List<String> projects) {
        super(OperationType.LIST_PROJECTS, OperationStatus.OK, message);
        this.projects = projects;
    }

    public List<String> getProjects() {
        return projects;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimateStrings(projects));
    }
}

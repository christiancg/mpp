package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class DeleteUserRequest extends OperationRequest {
    private String username;

    public DeleteUserRequest() {
        super(OperationType.DELETE_USER, null, null);
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}

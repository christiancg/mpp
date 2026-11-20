package org.ccg.mpp.ops.req;

import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.OperationType;

public class CreateUserRequest extends PermissionsRequest {
    private String password;

    public CreateUserRequest() {
        super(OperationType.CREATE_USER, null, null);
        this.admin = false;
        this.globalPermissions = new JsonArray();
        this.projectPermissions = new JsonObject();
        this.collectionPermissions = new JsonObject();
        this.scriptPermissions = new JsonObject();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

}

package org.ccg.mpp.ops.req;

import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.OperationType;

public class ChangePermissionsRequest extends PermissionsRequest {

    public ChangePermissionsRequest() {
        super(OperationType.CHANGE_PERMISSIONS, null, null);
        this.admin = false;
        this.globalPermissions = new JsonArray();
        this.projectPermissions = new JsonObject();
        this.collectionPermissions = new JsonObject();
        this.scriptPermissions = new JsonObject();
    }

}

package org.ccg.mpp.ops.req;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ops.OperationType;

public class SetDatabaseOwnersRequest extends OperationRequest {
    private JsonArray owners;

    public SetDatabaseOwnersRequest(String databaseName) {
        super(OperationType.SET_DATABASE_OWNERS, databaseName, null);
        this.owners = new JsonArray();
    }

    public void setOwners(List<String> ownersList) {
        this.owners = new JsonArray();
        ownersList.forEach(o -> this.owners.add(new JsonString(o)));
    }

    public List<String> getOwners() {
        if (owners == null)
            return new ArrayList<>();
        return owners.asList().stream().map(el -> el.asJsonString().getValue()).collect(Collectors.toList());
    }
}

package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class ReleaseFaultRequest extends OperationRequest {
    private String point;

    public ReleaseFaultRequest() {
        super(OperationType.RELEASE_FAULT, null, null);
    }

    public String getPoint() {
        return point;
    }

    public void setPoint(String point) {
        this.point = point;
    }
}

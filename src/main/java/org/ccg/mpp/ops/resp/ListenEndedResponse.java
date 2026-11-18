package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationType;

public class ListenEndedResponse extends OperationResponse {
    public String listenId;

    public ListenEndedResponse(String listenId, String reason) {
        super(OperationType.LISTEN, ErrorCode.LISTEN_ENDED.getDefaultMessage() + ": " + reason, ErrorCode.LISTEN_ENDED);
        this.listenId = listenId;
    }

    public String getListenId() {
        return listenId;
    }

    public void setListenId(String listenId) {
        this.listenId = listenId;
    }
}

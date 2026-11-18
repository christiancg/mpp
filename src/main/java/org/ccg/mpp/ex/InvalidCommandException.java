package org.ccg.mpp.ex;

import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationType;

public class InvalidCommandException extends RuntimeException {
    private final OperationType requestType;

    public InvalidCommandException(Exception exception) {
        this(null, exception);
    }

    public InvalidCommandException(OperationType requestType, Exception exception) {
        super(ErrorCode.INVALID_COMMAND.getDefaultMessage(), exception);
        this.requestType = requestType;
    }

    public OperationType getRequestType() {
        return requestType;
    }
}

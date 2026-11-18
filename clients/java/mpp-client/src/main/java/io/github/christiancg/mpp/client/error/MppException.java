package io.github.christiancg.mpp.client.error;

import io.github.christiancg.mpp.client.protocol.ErrorCode;
import io.github.christiancg.mpp.client.protocol.Status;
import java.util.Optional;

public class MppException extends RuntimeException {
    private final String rawCode;
    private final Status status;

    public MppException(String message, String rawCode, Status status) {
        super(message);
        this.rawCode = rawCode;
        this.status = status;
    }

    public MppException(String message, Throwable cause) {
        super(message, cause);
        this.rawCode = null;
        this.status = null;
    }

    public Optional<String> rawCode() {
        return Optional.ofNullable(rawCode);
    }

    public Optional<ErrorCode> errorCode() {
        return ErrorCode.fromCode(rawCode);
    }

    public Optional<Status> status() {
        return Optional.ofNullable(status);
    }

    public boolean hasCode(ErrorCode code) {
        return code.code().equals(rawCode);
    }
}

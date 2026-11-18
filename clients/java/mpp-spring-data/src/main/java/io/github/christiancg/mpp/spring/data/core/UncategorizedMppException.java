package io.github.christiancg.mpp.spring.data.core;

import java.io.Serial;
import org.springframework.dao.UncategorizedDataAccessException;

public class UncategorizedMppException extends UncategorizedDataAccessException {
    @Serial
    private static final long serialVersionUID = 1L;

    public UncategorizedMppException(String message, Throwable cause) {
        super(message, cause);
    }
}

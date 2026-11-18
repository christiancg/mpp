package org.ccg.mpp.unit.ejson.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.ccg.mpp.ejson.exceptions.MalformedJsonException;
import org.junit.jupiter.api.Test;

public class MalformedJsonExceptionTest {
    @Test
    public void test_exception_message_is_accessible() {
        String errorMessage = "Invalid JSON format";

        MalformedJsonException exception = new MalformedJsonException(errorMessage);

        assertEquals(errorMessage, exception.getMessage());
    }

    @Test
    public void test_exception_with_null_message() {
        MalformedJsonException exception = new MalformedJsonException(null);

        assertNull(exception.getMessage());
    }
}

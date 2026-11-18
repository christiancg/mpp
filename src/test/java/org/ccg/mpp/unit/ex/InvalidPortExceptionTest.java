package org.ccg.mpp.unit.ex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ex.InvalidPortException;
import org.junit.jupiter.api.Test;

public class InvalidPortExceptionTest {
    @Test
    public void test_create_exception_with_port_and_cause() {
        String invalidPort = "65536";
        NumberFormatException cause = new NumberFormatException("Port out of range");

        InvalidPortException exception = new InvalidPortException(invalidPort, cause);

        assertEquals("Invalid port: 65536", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    @Test
    public void test_create_exception_with_null_port() {
        IllegalArgumentException cause = new IllegalArgumentException("Port cannot be null");

        InvalidPortException exception = new InvalidPortException(null, cause);

        assertEquals("Invalid port: null", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }
}

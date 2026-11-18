package org.ccg.mpp.unit.ejson.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ejson.exceptions.MissingEndOfStringException;
import org.junit.jupiter.api.Test;

public class MissingEndOfStringExceptionTest {
    @Test
    public void test_default_message() {
        MissingEndOfStringException exception = new MissingEndOfStringException();

        assertEquals("Missing end of string on json", exception.getMessage());
    }

    @Test
    public void test_nested_exception() {
        MissingEndOfStringException innerException = new MissingEndOfStringException();
        RuntimeException outerException = new RuntimeException("Outer exception", innerException);

        assertEquals(innerException, outerException.getCause());
        assertEquals("Missing end of string on json", outerException.getCause().getMessage());
    }
}

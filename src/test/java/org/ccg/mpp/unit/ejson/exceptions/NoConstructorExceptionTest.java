package org.ccg.mpp.unit.ejson.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.ccg.mpp.ejson.exceptions.NoConstructorException;
import org.junit.jupiter.api.Test;

public class NoConstructorExceptionTest {
    @Test
    public void test_create_exception_with_valid_class() {
        NoConstructorException exception = new NoConstructorException(String.class);

        assertEquals("Couldn't find a suitable constructor for class java.lang.String", exception.getMessage());
    }

    @Test
    public void test_create_exception_with_null_class() {
        //noinspection ThrowableNotThrown,DataFlowIssue
        assertThrows(NullPointerException.class, () -> new NoConstructorException(null));
    }
}

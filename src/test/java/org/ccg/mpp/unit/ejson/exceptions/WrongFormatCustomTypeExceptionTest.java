package org.ccg.mpp.unit.ejson.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.ccg.mpp.ejson.exceptions.WrongFormatCustomTypeException;
import org.junit.jupiter.api.Test;

public class WrongFormatCustomTypeExceptionTest {
    @Test
    public void test_create_exception_with_classname_and_cause() {
        String className = "TestClass";
        Exception cause = new RuntimeException("Test cause");

        WrongFormatCustomTypeException exception = new WrongFormatCustomTypeException(className, cause);

        assertEquals("The format for the custom type with class name TestClass is wrong.", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    @Test
    public void test_create_exception_with_null_classname() {
        WrongFormatCustomTypeException exception = new WrongFormatCustomTypeException(null);

        assertEquals("The format for the custom type with class name null is wrong.", exception.getMessage());
        assertNull(exception.getCause());
    }
}

package org.ccg.mpp.unit.ejson.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ejson.exceptions.NonRegisteredCustomTypeException;
import org.junit.jupiter.api.Test;

public class NonRegisteredCustomTypeExceptionTest {
    @Test
    public void test_exception_message_contains_custom_type_name() {
        String customTypeName = "MyCustomType";
        NonRegisteredCustomTypeException exception = new NonRegisteredCustomTypeException(customTypeName);

        String expectedMessage = "The custom type MyCustomType has not been registered";
        assertEquals(expectedMessage, exception.getMessage());
    }

    @Test
    public void test_exception_message_with_null_type_name() {
        NonRegisteredCustomTypeException exception = new NonRegisteredCustomTypeException(null);

        String expectedMessage = "The custom type null has not been registered";
        assertEquals(expectedMessage, exception.getMessage());
    }
}

package org.ccg.mpp.ejson.exceptions;

public class InvalidSchemaException extends RuntimeException {
    public InvalidSchemaException(String error) {
        super(error);
    }
}

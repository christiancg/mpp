package org.ccg.mpp.simplejs.exceptions;

public class UnexpectedCharacterException extends RuntimeException {
    public UnexpectedCharacterException(Character character, int position) {
        super("Unexpected character " + character + " at position: " + position);
    }
}

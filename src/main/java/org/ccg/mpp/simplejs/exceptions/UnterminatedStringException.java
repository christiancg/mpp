package org.ccg.mpp.simplejs.exceptions;

public class UnterminatedStringException extends RuntimeException {
    public UnterminatedStringException(int position) {
        super("Unterminated string starting at position: " + position);
    }
}

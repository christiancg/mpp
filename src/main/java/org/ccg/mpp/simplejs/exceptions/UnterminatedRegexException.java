package org.ccg.mpp.simplejs.exceptions;

public class UnterminatedRegexException extends RuntimeException {
    public UnterminatedRegexException(int position) {
        super("Unterminated regular expression starting at position: " + position);
    }
}

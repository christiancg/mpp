package org.ccg.mpp.simplejs.exceptions;

public class UnterminatedTemplateException extends RuntimeException {
    public UnterminatedTemplateException(int position) {
        super("Unterminated template literal starting at position: " + position);
    }
}

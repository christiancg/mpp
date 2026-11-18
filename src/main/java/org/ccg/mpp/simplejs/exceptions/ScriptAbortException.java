package org.ccg.mpp.simplejs.exceptions;

public class ScriptAbortException extends RuntimeException {
    public ScriptAbortException(String message) {
        super(message, null, false, false);
    }
}

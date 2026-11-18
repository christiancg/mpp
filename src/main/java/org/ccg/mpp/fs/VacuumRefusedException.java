package org.ccg.mpp.fs;

import java.io.IOException;

public class VacuumRefusedException extends IOException {
    public VacuumRefusedException(String message) {
        super(message);
    }
}

package org.ccg.mpp.ejson.validate;

import org.ccg.mpp.ejson.exceptions.SchemaBudgetExceededException;
import org.ccg.mpp.simplejs.internal.regex.RegexStepBudget;

public final class SchemaBudget implements RegexStepBudget {
    static final String EXCEEDED_MESSAGE = "Schema validation exceeded its evaluation budget";

    private long remaining;

    public SchemaBudget(long steps) {
        this.remaining = steps;
    }

    @Override
    public void step() {
        if (remaining <= 0) {
            throw new SchemaBudgetExceededException(EXCEEDED_MESSAGE);
        }
        remaining--;
    }

    @Override
    public void steps(int count) {
        if (remaining < count) {
            throw new SchemaBudgetExceededException(EXCEEDED_MESSAGE);
        }
        remaining -= count;
    }
}

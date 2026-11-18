package org.ccg.mpp.simplejs.internal.interpreter;

import static org.ccg.mpp.simplejs.internal.interpreter.InterpreterUtils.matchesLabel;

import org.ccg.mpp.simplejs.internal.Completion;

enum LoopAction {
    CONTINUE_LOOP, BREAK_LOOP, PROPAGATE;

    static LoopAction of(Completion completion, String label) {
        return switch (completion.kind()) {
            case NORMAL -> CONTINUE_LOOP;
            case CONTINUE -> matchesLabel(completion.label(), label) ? CONTINUE_LOOP : PROPAGATE;
            case BREAK -> matchesLabel(completion.label(), label) ? BREAK_LOOP : PROPAGATE;
            case RETURN -> PROPAGATE;
        };
    }
}

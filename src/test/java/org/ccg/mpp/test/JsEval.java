package org.ccg.mpp.test;

import org.ccg.mpp.simplejs.internal.Interpreter;
import org.ccg.mpp.simplejs.values.JsBoolean;
import org.ccg.mpp.simplejs.values.JsNumber;
import org.ccg.mpp.simplejs.values.JsString;

public final class JsEval {

    private JsEval() {
    }

    public static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    public static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    public static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }
}

package org.ccg.mpp.simplejs.internal.interpreter;

import java.util.List;
import org.ccg.mpp.simplejs.internal.Interpreter;
import org.ccg.mpp.simplejs.values.JsArguments;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsSymbol;
import org.ccg.mpp.simplejs.values.JsValue;

public record ArgumentsObjects(Interpreter interp) {
    public JsArguments make(List<JsValue> args) {
        return withOwnProperties(new JsArguments(args, null, null));
    }

    public JsArguments withOwnProperties(JsArguments arguments) {
        interp.intrinsics.poison(arguments, "callee");
        final var table = arguments.ownProperties();
        table.setFlags("callee", new JsObject.PropertyFlags(false, false, false));
        table.defineSymbolValue(JsSymbol.ITERATOR, interp.intrinsics.arrayProto.getSymbol(JsSymbol.ITERATOR));
        table.setSymbolFlags(JsSymbol.ITERATOR, JsObject.PropertyFlags.HIDDEN);
        return arguments;
    }
}

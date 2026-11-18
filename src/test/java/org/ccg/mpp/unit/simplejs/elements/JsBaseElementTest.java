package org.ccg.mpp.unit.simplejs.elements;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.List;
import org.ccg.mpp.simplejs.elements.JsBaseElement.JsType;
import org.ccg.mpp.simplejs.elements.JsBigInt;
import org.ccg.mpp.simplejs.elements.JsBoolean;
import org.ccg.mpp.simplejs.elements.JsEOF;
import org.ccg.mpp.simplejs.elements.JsIdentifier;
import org.ccg.mpp.simplejs.elements.JsKeyword;
import org.ccg.mpp.simplejs.elements.JsNull;
import org.ccg.mpp.simplejs.elements.JsNumber;
import org.ccg.mpp.simplejs.elements.JsOperator;
import org.ccg.mpp.simplejs.elements.JsPrivateIdentifier;
import org.ccg.mpp.simplejs.elements.JsRegex;
import org.ccg.mpp.simplejs.elements.JsSeparator;
import org.ccg.mpp.simplejs.elements.JsString;
import org.ccg.mpp.simplejs.elements.JsTemplateString;
import org.ccg.mpp.simplejs.elements.JsUndefined;
import org.junit.jupiter.api.Test;

public class JsBaseElementTest {
    @Test
    public void test_get_type_for_each_subclass() {
        assertEquals(JsType.KEYWORD, new JsKeyword("if").getType());
        assertEquals(JsType.IDENTIFIER, new JsIdentifier("x").getType());
        assertEquals(JsType.PRIVATE_IDENTIFIER, new JsPrivateIdentifier("x").getType());
        assertEquals(JsType.NUMBER, new JsNumber(1.0).getType());
        assertEquals(JsType.BIGINT, new JsBigInt(BigInteger.ONE).getType());
        assertEquals(JsType.STRING, new JsString("s").getType());
        assertEquals(JsType.BOOLEAN, new JsBoolean(true).getType());
        assertEquals(JsType.NULL, JsNull.getInstance().getType());
        assertEquals(JsType.UNDEFINED, JsUndefined.getInstance().getType());
        assertEquals(JsType.OPERATOR, new JsOperator("+").getType());
        assertEquals(JsType.SEPARATOR, new JsSeparator(';').getType());
        assertEquals(JsType.REGEX, new JsRegex("a", "g").getType());
        assertEquals(JsType.TEMPLATE_STRING, new JsTemplateString(List.of(""), List.of(""), List.of()).getType());
        assertEquals(JsType.EOF, JsEOF.getInstance().getType());
    }
}

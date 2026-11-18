package org.ccg.mpp.simplejs.internal.lexer;

import org.ccg.mpp.simplejs.elements.JsBaseElement;

public record Lexed(JsBaseElement token, int next) {
}

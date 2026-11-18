package org.ccg.mpp.simplejs.internal.parser;

import java.util.List;
import org.ccg.mpp.simplejs.nodes.BlockStatement;
import org.ccg.mpp.simplejs.nodes.JsNode;

public record FunctionParts(List<JsNode> params, BlockStatement body) {
}

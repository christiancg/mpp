package org.ccg.mpp.simplejs.internal.interpreter;

import org.ccg.mpp.simplejs.nodes.FieldDefinition;
import org.ccg.mpp.simplejs.nodes.StaticBlock;
import org.ccg.mpp.simplejs.values.JsValue;

public record StaticEntry(FieldDefinition field, StaticBlock block, JsValue key) {
}

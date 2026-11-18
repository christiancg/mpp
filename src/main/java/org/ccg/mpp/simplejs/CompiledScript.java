package org.ccg.mpp.simplejs;

import org.ccg.mpp.simplejs.nodes.Program;

public record CompiledScript(Program program, String source, boolean strictScriptGoal) {
}

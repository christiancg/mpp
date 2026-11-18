package io.github.christiancg.mpp.client.query;

public sealed interface MidOperation permits ArrayOperation, FieldFunction, CastOperation, ScriptOperation {
}

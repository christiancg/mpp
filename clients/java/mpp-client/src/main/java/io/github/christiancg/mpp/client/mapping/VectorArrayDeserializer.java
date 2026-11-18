package io.github.christiancg.mpp.client.mapping;

import io.github.christiancg.mpp.client.internal.CustomValues;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public final class VectorArrayDeserializer extends ValueDeserializer<double[]> {
    @Override
    public double[] deserialize(JsonParser parser, DeserializationContext context) {
        return CustomValues.parseVector(parser.getValueAsString()).toArray();
    }
}

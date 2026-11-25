package org.ccg.mpp.ejson.internal;

import java.util.HashMap;
import java.util.Map;
import org.ccg.mpp.ejson.WireName;

public final class EnumWireNames {
    private static final ClassValue<Names> NAMES = new ClassValue<>() {
        @Override
        @SuppressWarnings("NullableProblems")
        protected Names computeValue(Class<?> type) {
            return Names.of(type);
        }
    };

    private EnumWireNames() {
    }

    public static String of(Enum<?> constant) {
        return NAMES.get(constant.getDeclaringClass()).wireNames[constant.ordinal()];
    }

    public static Object constantOf(Class<?> enumType, String name) {
        return NAMES.get(enumType).byName.get(name);
    }

    private record Names(String[] wireNames, Map<String, Object> byName) {
        static Names of(Class<?> type) {
            final var constants = type.getEnumConstants();
            final var wireNames = new String[constants.length];
            final var byName = new HashMap<String, Object>(Math.max(4, constants.length * 4));
            for (var i = 0; i < constants.length; i++) {
                final var constant = (Enum<?>) constants[i];
                wireNames[i] = wireNameOf(type, constant);
                byName.put(constant.name(), constant);
                byName.putIfAbsent(wireNames[i], constant);
            }
            return new Names(wireNames, Map.copyOf(byName));
        }

        private static String wireNameOf(Class<?> type, Enum<?> constant) {
            try {
                final var annotation = type.getField(constant.name()).getAnnotation(WireName.class);
                return annotation == null ? constant.name() : annotation.value();
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

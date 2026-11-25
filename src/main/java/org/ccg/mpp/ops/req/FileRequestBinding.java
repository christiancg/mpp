package org.ccg.mpp.ops.req;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.ccg.mpp.data.FileEncoding;
import org.ccg.mpp.data.FileWriteMode;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;

final class FileRequestBinding {
    static final String ENCODING = "encoding";
    static final String MODE = "mode";
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final Set<String> OPTIONS = Set.of(ENCODING, MODE);

    private FileRequestBinding() {
    }

    static WriteFileRequest write(JsonObject root) {
        final var request = eJson.fromJson(root, WriteFileRequest.class, OPTIONS);
        request.setEncoding(option(root, ENCODING, FileEncoding::parse, request::setUnrecognizedOption));
        request.setMode(option(root, MODE, FileWriteMode::parse, request::setUnrecognizedOption));
        return request;
    }

    static ReadFileRequest read(JsonObject root) {
        final var request = eJson.fromJson(root, ReadFileRequest.class, OPTIONS);
        request.setEncoding(option(root, ENCODING, FileEncoding::parse, request::setUnrecognizedOption));
        return request;
    }

    private static <T> T option(JsonObject root, String key, Function<String, T> parser, Consumer<String> rejected) {
        final var value = root.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        final var parsed = value.isJsonString() ? parser.apply(value.asJsonString().getValue()) : null;
        if (parsed == null) {
            rejected.accept(key);
        }
        return parsed;
    }
}

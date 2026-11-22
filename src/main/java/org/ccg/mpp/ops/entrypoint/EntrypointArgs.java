package org.ccg.mpp.ops.entrypoint;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonNull;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ejson.internal.JsonParser;
import org.ccg.mpp.http.HttpRequest;
import org.ccg.mpp.http.MalformedEncodingException;
import org.ccg.mpp.http.QueryString;

public final class EntrypointArgs {
    private static final String JSON_MEDIA_TYPE = "application/json";
    private static final String JSON_SUFFIX = "+json";
    private static final int TOKEN_SLACK = 16;

    private EntrypointArgs() {
    }

    public static JsonObject of(HttpRequest request, String entrypointName, String path, Map<String, String> pathParams,
            byte[] body) {
        final var args = new JsonObject();
        args.add("entrypoint", new JsonString(entrypointName));
        args.add("method", new JsonString(request.head().method()));
        args.add("path", new JsonString(path));
        args.add("pathParams", stringsOf(pathParams));
        args.add("query", queryOf(request.head().query()));
        args.add("headers", stringsOf(request.head().headers()));
        args.add("body", bodyOf(request.head().header("Content-Type"), body));
        args.add("remoteAddress", new JsonString(request.remoteAddress()));
        return args;
    }

    private static JsonObject stringsOf(Map<String, String> values) {
        final var json = new JsonObject();
        values.forEach((key, value) -> json.add(key, new JsonString(value)));
        return json;
    }

    private static JsonObject queryOf(String rawQuery) {
        final var json = new JsonObject();
        try {
            QueryString.parse(rawQuery).forEach((key, values) -> {
                if (values.size() == 1) {
                    json.add(key, new JsonString(values.getFirst()));
                } else {
                    final var array = new JsonArray();
                    values.forEach(array::add);
                    json.add(key, array);
                }
            });
        } catch (MalformedEncodingException e) {
            throw new MalformedBodyException("The query string is not valid: " + e.getMessage(), e);
        }
        return json;
    }

    private static JsonBaseElement bodyOf(String contentType, byte[] body) {
        if (body.length == 0) {
            return JsonNull.INSTANCE;
        }
        if (isJson(contentType)) {
            try {
                return JsonParser.parse(body, 0, body.length, Globals.MAX_REQUEST_NESTING_DEPTH,
                        body.length / Globals.MIN_BYTES_PER_REQUEST_TOKEN + TOKEN_SLACK);
            } catch (RuntimeException e) {
                throw new MalformedBodyException("The JSON body could not be parsed: " + e.getMessage(), e);
            }
        }
        return new JsonString(utf8(body));
    }

    static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        final var semicolon = contentType.indexOf(';');
        final var mediaType = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim()
                .toLowerCase(Locale.ROOT);
        return JSON_MEDIA_TYPE.equals(mediaType) || mediaType.endsWith(JSON_SUFFIX);
    }

    private static String utf8(byte[] body) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            throw new MalformedBodyException("The body is not UTF-8 text", e);
        }
    }
}

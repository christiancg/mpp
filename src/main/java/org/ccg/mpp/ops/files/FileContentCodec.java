package org.ccg.mpp.ops.files;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.ccg.mpp.data.FileEncoding;

public final class FileContentCodec {
    private FileContentCodec() {
    }

    public static byte[] decode(String content, FileEncoding encoding) throws InvalidFileContentException {
        if (encoding == FileEncoding.UTF8) {
            return content.getBytes(StandardCharsets.UTF_8);
        }
        try {
            return Base64.getDecoder().decode(content);
        } catch (IllegalArgumentException e) {
            throw new InvalidFileContentException("content is not valid base64: " + e.getMessage());
        }
    }

    public static String encode(byte[] bytes, FileEncoding encoding) throws InvalidFileContentException {
        if (encoding != FileEncoding.UTF8) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new InvalidFileContentException(
                    "these bytes are not valid UTF-8 (a binary file, or a window that splits a character); read them"
                            + " as base64");
        }
    }

    public static final class InvalidFileContentException extends Exception {
        public InvalidFileContentException(String message) {
            super(message);
        }
    }
}

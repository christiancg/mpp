package org.ccg.mpp.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class PercentDecoding {
    private static final int HEX_RADIX = 16;
    private static final char LAST_ASCII = 0x7F;
    private static final char LAST_OCTET = 0xFF;
    private static final String NOT_TWO_HEX_DIGITS = "A '%' must be followed by two hexadecimal digits";

    private PercentDecoding() {
    }

    public static String decode(String encoded, boolean plusIsSpace) {
        if (isPlain(encoded, plusIsSpace)) {
            return encoded;
        }
        final var bytes = new ByteArrayOutputStream(encoded.length());
        for (var i = 0; i < encoded.length(); i++) {
            final var c = encoded.charAt(i);
            if (c == '%') {
                bytes.write(hexByte(encoded, i));
                i += 2;
            } else if (c == '+' && plusIsSpace) {
                bytes.write(' ');
            } else if (c <= LAST_OCTET) {
                bytes.write(c);
            } else {
                throw new MalformedEncodingException("The request line holds a character that is not a byte");
            }
        }
        return utf8(bytes.toByteArray());
    }

    private static boolean isPlain(String encoded, boolean plusIsSpace) {
        for (var i = 0; i < encoded.length(); i++) {
            final var c = encoded.charAt(i);
            if (c == '%' || c > LAST_ASCII || c == '+' && plusIsSpace) {
                return false;
            }
        }
        return true;
    }

    private static int hexByte(String encoded, int percentAt) {
        if (percentAt + 2 >= encoded.length()) {
            throw new MalformedEncodingException(NOT_TWO_HEX_DIGITS);
        }
        final var high = Character.digit(encoded.charAt(percentAt + 1), HEX_RADIX);
        final var low = Character.digit(encoded.charAt(percentAt + 2), HEX_RADIX);
        if (high < 0 || low < 0) {
            throw new MalformedEncodingException(NOT_TWO_HEX_DIGITS);
        }
        return high * HEX_RADIX + low;
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new MalformedEncodingException("The percent-encoded bytes are not UTF-8");
        }
    }
}

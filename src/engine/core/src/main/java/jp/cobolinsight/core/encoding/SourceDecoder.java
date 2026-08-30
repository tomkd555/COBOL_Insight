package jp.cobolinsight.core.encoding;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;

/**
 * Decodes a raw byte array and normalizes it to UTF-16, returning a {@link DecodedSource} that
 * retains the original byte array and offset table. A manually specified code page takes
 * precedence over automatic detection.
 */
public final class SourceDecoder {

    /** Byte order mark (U+FEFF) that may appear at the start of the decoded output. */
    private static final char BYTE_ORDER_MARK = '﻿';

    private final CodePageDetector detector = new CodePageDetector();

    /** Decodes using automatic detection. */
    public DecodedSource decode(byte[] bytes) {
        DetectionResult detection = detector.detect(bytes);
        EncodingInfo info = new EncodingInfo(
                detection.codePage(), detection.confidence(), detection.soSiPresent(), false);
        return decodeWith(bytes, info);
    }

    /** Decodes using a manually specified code page. The result of automatic detection is not used. */
    public DecodedSource decode(byte[] bytes, CodePage codePage) {
        EncodingInfo info = new EncodingInfo(codePage, 100, CodePageDetector.containsSoSi(bytes), true);
        return decodeWith(bytes, info);
    }

    private static DecodedSource decodeWith(byte[] bytes, EncodingInfo info) {
        CharsetDecoder charsetDecoder = info.codePage().charset().newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        // Producing one character requires at least one byte, so the number of characters
        // produced never exceeds the number of bytes.
        CharBuffer out = CharBuffer.allocate(bytes.length + 1);
        // Reserve one extra slot at the end for the character position charCount (the element
        // pointing at the total byte length).
        int[] charStarts = new int[bytes.length + 1];
        int pendingStart = 0;

        // Decode by widening the input one byte at a time, mapping each produced character to
        // the start of the not-yet-mapped byte run. Bytes consumed without producing a
        // character, such as SO/SI, are excluded from the mapping.
        if (bytes.length == 0) {
            // CharsetDecoder.flush cannot be called until a decode with endOfInput=true has
            // completed. When the input is empty the loop below never runs, so call it here.
            charsetDecoder.decode(in, out, true);
        }
        for (int limit = 1; limit <= bytes.length; limit++) {
            in.limit(limit);
            int before = out.position();
            CoderResult result = charsetDecoder.decode(in, out, limit == bytes.length);
            requireNoError(result, info, limit);
            pendingStart = record(charStarts, out, before, pendingStart, in.position());
        }
        int before = out.position();
        requireNoError(charsetDecoder.flush(out), info, bytes.length);
        record(charStarts, out, before, pendingStart, bytes.length);

        // The byte order mark is an encoding signal, not a character of the source text. Leaving
        // it in the text would shift line 1's columns by one, throwing off every column in the
        // fixed-format sequence-number and indicator-area fields. The original byte array and
        // offset table are preserved regardless.
        String decoded = new String(out.array(), 0, out.position());
        int from = decoded.isEmpty() || decoded.charAt(0) != BYTE_ORDER_MARK ? 0 : 1;
        String text = decoded.substring(from);
        int[] table = Arrays.copyOfRange(charStarts, from, from + text.length() + 1);
        table[text.length()] = bytes.length;
        return new DecodedSource(text, bytes, new ByteOffsetTable(text, table), info);
    }

    private static int record(int[] charStarts, CharBuffer out, int before, int pendingStart, int consumedTo) {
        for (int i = before; i < out.position(); i++) {
            charStarts[i] = pendingStart;
        }
        return Math.max(pendingStart, consumedTo);
    }

    private static void requireNoError(CoderResult result, EncodingInfo info, int byteOffset) {
        if (result.isError()) {
            throw new IllegalArgumentException(
                    "コードページ " + info.codePage().charsetName() + " で復号できないバイト列がある(オフセット "
                            + byteOffset + " 付近)");
        }
    }
}

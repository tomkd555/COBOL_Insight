package jp.cobolinsight.core.source;

import java.util.Arrays;
import java.util.Objects;

/**
 * A decoded source. Keeps the original byte array as-is, plus a table (charByteOffsets) that
 * maps each character position in text to its offset in the original byte array. The arrays are
 * defensively copied both on intake and on return.
 */
public record DecodedSource(String path, String text, byte[] originalBytes, int[] charByteOffsets,
        EncodingInfo encoding) {

    public DecodedSource {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(originalBytes, "originalBytes");
        Objects.requireNonNull(charByteOffsets, "charByteOffsets");
        Objects.requireNonNull(encoding, "encoding");
        if (charByteOffsets.length != text.length()) {
            throw new IllegalArgumentException("charByteOffsets length " + charByteOffsets.length
                    + " must equal text length " + text.length());
        }
        originalBytes = originalBytes.clone();
        charByteOffsets = charByteOffsets.clone();
    }

    @Override
    public byte[] originalBytes() {
        return originalBytes.clone();
    }

    @Override
    public int[] charByteOffsets() {
        return charByteOffsets.clone();
    }

    /** The offset in the original byte array where the character at the given position in text begins. charIndex must be 0 or greater and less than text's length. */
    public int byteOffsetAt(int charIndex) {
        return charByteOffsets[charIndex];
    }

    // A record's default implementation compares array fields by reference, so equals and hashCode are overridden to compare by content.
    @Override
    public boolean equals(Object obj) {
        return obj instanceof DecodedSource other
                && path.equals(other.path)
                && text.equals(other.text)
                && Arrays.equals(originalBytes, other.originalBytes)
                && Arrays.equals(charByteOffsets, other.charByteOffsets)
                && encoding.equals(other.encoding);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, text, Arrays.hashCode(originalBytes),
                Arrays.hashCode(charByteOffsets), encoding);
    }

    // Avoids dumping the original byte array and the full source text to logs; shows only a summary.
    @Override
    public String toString() {
        return "DecodedSource[path=" + path + ", byteSize=" + originalBytes.length
                + ", encoding=" + encoding + "]";
    }
}

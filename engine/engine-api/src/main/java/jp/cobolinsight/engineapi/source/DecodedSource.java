package jp.cobolinsight.engineapi.source;

import java.util.Arrays;
import java.util.Objects;

/**
 * 復号済みソース。text の各文字位置から原バイト列上のオフセットへの対応表(charByteOffsets)を
 * 保持し、原バイト列を温存する。配列は取込・返却の双方で防御的にコピーする。
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

    public int byteOffsetAt(int charIndex) {
        return charByteOffsets[charIndex];
    }

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

    @Override
    public String toString() {
        return "DecodedSource[path=" + path + ", byteSize=" + originalBytes.length
                + ", encoding=" + encoding + "]";
    }
}

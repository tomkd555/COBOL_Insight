package jp.cobolinsight.core.easytrieve;

import jp.cobolinsight.core.source.SourcePosition;

import java.util.Objects;
import java.util.Optional;

/**
 * One field definition. {@code file} is the file whose record the field lies in, empty for working
 * storage. {@code location} is the start location as written: a number, {@code *} for the next
 * free position, {@code W} or {@code S} for working storage, or the name of the field this one
 * overlays. {@code start} is the 1-based position in the record once that is known, and
 * {@code length} the length in bytes; either is empty where the definition leaves it to something
 * the source does not state (a {@code LIKE}, a field the Db2 catalogue defines).
 *
 * <p>{@code type} is the data format letter: {@code A} alphanumeric, {@code N} zoned decimal,
 * {@code P} packed, {@code B} binary, {@code U} unsigned packed, {@code K} and {@code M} for
 * double-byte data, or the empty string where none is written.
 */
public record EasytrieveField(String name, String file, String location, Optional<Integer> start,
        Optional<Integer> length, String type, int decimals, int occurs, boolean hasValue,
        SourcePosition position) {

    public EasytrieveField {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(length, "length");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(position, "position");
    }

    /** Whether the field lies in working storage rather than in the record of a file. */
    public boolean working() {
        return file.isEmpty();
    }

    /** Whether the field holds a number the program computes with. */
    public boolean numeric() {
        return type.equals("N") || type.equals("P") || type.equals("B") || type.equals("U");
    }

    /** The last record position the field occupies, every occurrence counted, when both are known. */
    public Optional<Integer> end() {
        if (start.isEmpty() || length.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(start.get() + length.get() * Math.max(1, occurs) - 1);
    }
}

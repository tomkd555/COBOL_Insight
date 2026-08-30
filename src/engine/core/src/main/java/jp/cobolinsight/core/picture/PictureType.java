package jp.cobolinsight.core.picture;

import java.util.Locale;
import java.util.Objects;

/**
 * The result of parsing a PICTURE clause and USAGE. No side effects, immutable.
 *
 * <p>{@code integerDigits} and {@code fractionDigits} give the digit counts of the integer and
 * fractional parts of a numeric item. {@code totalDigits} is {@code integerDigits + fractionDigits}
 * for numeric items, and the character position count for alphanumeric/alphabetic items.
 * {@code V} (implied decimal point) and {@code S} (sign) are not counted as digits; they are
 * reflected in {@code signed} instead.
 */
public record PictureType(PictureCategory category, boolean signed, int integerDigits,
        int fractionDigits, int totalDigits, boolean isNumeric, Usage usage) {

    /**
     * Computes the stored byte length.
     * <ul>
     *   <li>DISPLAY: the digit count (total digits for numeric items, character count for
     *       alphanumeric items). The sign uses overpunch, so it adds no bytes.
     *   <li>PACKED_DECIMAL (COMP-3): {@code ceil((total digits + 1) / 2)}.
     *   <li>BINARY (COMP/COMP-4/COMP-5): by digit count, 1-4 digits -> 2, 5-9 digits -> 4,
     *       10-18 digits -> 8 bytes.
     * </ul>
     */
    public int byteLength() {
        return switch (usage) {
            case DISPLAY -> totalDigits;
            case PACKED_DECIMAL -> (totalDigits + 2) / 2;
            case BINARY -> binaryBytes(totalDigits);
        };
    }

    private static int binaryBytes(int digits) {
        if (digits <= 4) {
            return 2;
        }
        if (digits <= 9) {
            return 4;
        }
        return 8;
    }

    /** Parses a PICTURE clause and USAGE. If {@code usage} is unspecified (null/empty), treats it as DISPLAY. */
    public static PictureType parse(String picture, String usage) {
        Objects.requireNonNull(picture, "picture");
        String pic = picture.trim().toUpperCase(Locale.ROOT);
        if (pic.isEmpty()) {
            throw new IllegalArgumentException("picture must not be blank");
        }
        boolean signed = false;
        int integerDigits = 0;
        int fractionDigits = 0;
        int charPositions = 0;
        boolean sawV = false;
        boolean hasNine = false;
        boolean hasX = false;
        boolean hasA = false;
        boolean hasEdit = false;
        int i = 0;
        while (i < pic.length()) {
            char symbol = pic.charAt(i);
            i++;
            if (symbol == 'S') {
                signed = true;
                continue;
            }
            if (symbol == 'V') {
                sawV = true;
                continue;
            }
            int count = 1;
            if (i < pic.length() && pic.charAt(i) == '(') {
                int close = pic.indexOf(')', i);
                if (close < 0) {
                    throw new IllegalArgumentException("unclosed '(' in picture: " + picture);
                }
                count = Integer.parseInt(pic.substring(i + 1, close).trim());
                i = close + 1;
            }
            switch (symbol) {
                case '9' -> {
                    hasNine = true;
                    charPositions += count;
                    if (sawV) {
                        fractionDigits += count;
                    } else {
                        integerDigits += count;
                    }
                }
                case 'X' -> {
                    hasX = true;
                    charPositions += count;
                }
                case 'A' -> {
                    hasA = true;
                    charPositions += count;
                }
                case 'P' -> {
                    // Assumed decimal scaling position. Not counted as a digit since it has no storage position.
                }
                default -> {
                    // Editing symbols such as Z, *, comma, period, $, +, -. They occupy a character position but are not counted as numeric digits.
                    hasEdit = true;
                    charPositions += count;
                }
            }
        }
        PictureCategory category = classify(hasX, hasA, hasNine, hasEdit);
        boolean isNumeric = category == PictureCategory.NUMERIC;
        int totalDigits = isNumeric ? integerDigits + fractionDigits : charPositions;
        return new PictureType(category, signed, integerDigits, fractionDigits, totalDigits,
                isNumeric, Usage.normalize(usage));
    }

    private static PictureCategory classify(boolean hasX, boolean hasA, boolean hasNine,
            boolean hasEdit) {
        if (hasX) {
            return hasEdit ? PictureCategory.ALPHANUMERIC_EDITED : PictureCategory.ALPHANUMERIC;
        }
        if (hasNine) {
            return hasEdit ? PictureCategory.NUMERIC_EDITED : PictureCategory.NUMERIC;
        }
        if (hasA) {
            return hasEdit ? PictureCategory.ALPHANUMERIC_EDITED : PictureCategory.ALPHABETIC;
        }
        if (hasEdit) {
            return PictureCategory.NUMERIC_EDITED;
        }
        return PictureCategory.UNKNOWN;
    }
}

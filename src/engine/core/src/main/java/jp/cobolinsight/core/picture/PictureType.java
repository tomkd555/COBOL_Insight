package jp.cobolinsight.core.picture;

import java.util.Locale;
import java.util.Objects;

/**
 * PICTURE 句と USAGE を解析した結果。副作用なし・不変。
 *
 * <p>{@code integerDigits} と {@code fractionDigits} は数字項目の整数部・小数部の桁数を表す。
 * {@code totalDigits} は数字項目では {@code integerDigits + fractionDigits}、英数字・英字項目では
 * 文字位置数である。
 * {@code V}(暗黙小数点)と {@code S}(符号)は桁として数えず、{@code signed} に反映する。
 */
public record PictureType(PictureCategory category, boolean signed, int integerDigits,
        int fractionDigits, int totalDigits, boolean isNumeric, Usage usage) {

    /**
     * 格納バイト長を算出する。
     * <ul>
     *   <li>DISPLAY: 桁数(数字項目は総桁数、英数字は文字数)。符号はオーバーパンチで加算なし。
     *   <li>PACKED_DECIMAL(COMP-3): {@code ceil((総桁数 + 1) / 2)}。
     *   <li>BINARY(COMP/COMP-4/COMP-5): 桁数により 1-4桁→2、5-9桁→4、10-18桁→8 バイト。
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

    /** PICTURE と USAGE を解析する。{@code usage} が無指定(null/空)なら DISPLAY として扱う。 */
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
                    // 想定小数位取り。格納位置を持たないため桁数に数えない。
                }
                default -> {
                    // Z・*・,・.・$・+・- などの編集用記号。文字位置は占めるが数字桁には数えない。
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

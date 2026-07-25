package jp.cobolinsight.fix;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 挿入する文を固定形式の桁規則へ収めるノーマライザ。
 *
 * <p>桁は1始まり。一連番号欄(1-6桁)・7桁目指示欄・A領域(8-11桁)を空白とし、本文はB領域起点の
 * 12桁目から並べる。本文が72桁を超える場合、既定は<b>語境界(空白)での折り返し</b>である。この折り返しは
 * 継続指示を置かず、改行を語の区切り(空白)として扱って次の語をB領域起点の新しい物理行へ送る。
 * したがって折り返しで隣接する語が結合することはない。
 *
 * <p>1つの語やリテラルが単独でB領域(12-72桁)へ収まらない場合に限り、継続行(7桁目に {@code -})で
 * 途中分割する。リテラルを途中分割するときは、COBOL の継続規則に従い継続行のB領域先頭へ開き引用符を
 * 再挿入する。分割途中の物理行は72桁ちょうどまで本文で埋める。72桁に満たない行末の欄はコンパイラが
 * 空白とみなし、リテラルの途中分割ではその空白が黙って literal 値へ混入するためである。
 *
 * <p>桁計算は引数の {@link Charset} 相対にバイト単位で行う。同じ文字でも Shift_JIS(全角2バイト)と
 * UTF-8(全角3バイト)で1行に収まる文字数が変わる。全角文字は分断しない。
 *
 * <p>先頭11桁は ASCII 空白(1バイト)なので、B領域(12-72桁)へ収まる本文のバイト予算は
 * {@code 72 - 11 = 61} バイトである。
 */
public final class FixedFormatNormalizer {

    /** B領域の開始桁(1始まり)。 */
    public static final int B_AREA_START_COLUMN = 12;
    /** 本文を収められる最終桁(1始まり)。73桁目以降は識別欄で不可侵。 */
    public static final int CONTENT_END_COLUMN = 72;
    /** 7桁目指示欄の継続指示。 */
    private static final char CONTINUATION_INDICATOR = '-';

    /** B領域起点の物理行の接頭(1-11桁の空白)。継続指示は置かない。 */
    private static final String B_AREA_PREFIX = " ".repeat(B_AREA_START_COLUMN - 1);
    /** 語/リテラルを途中分割する継続行の接頭(7桁目に継続指示)。 */
    private static final String CONTINUATION_PREFIX =
            " ".repeat(6) + CONTINUATION_INDICATOR + " ".repeat(B_AREA_START_COLUMN - 1 - 7);
    /** B領域(12-72桁)へ収まる本文のバイト予算。 */
    private static final int B_AREA_BYTE_BUDGET = CONTENT_END_COLUMN - (B_AREA_START_COLUMN - 1);

    /**
     * B領域へ収めた1行以上の物理行を返す(行終端は含まない)。72バイト超は語境界で折り返し、
     * 単独で収まらない語・リテラルのみ継続行で途中分割する。
     */
    public List<String> layoutStatement(String statement, Charset charset) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentBytes = 0;

        for (String token : tokenize(statement)) {
            int tokenBytes = byteLength(token, charset);
            if (currentBytes == 0) {
                if (tokenBytes <= B_AREA_BYTE_BUDGET) {
                    current.append(token);
                    currentBytes = tokenBytes;
                } else {
                    splitOversizedToken(token, charset, lines);
                }
            } else if (currentBytes + 1 + tokenBytes <= B_AREA_BYTE_BUDGET) {
                current.append(' ').append(token);
                currentBytes += 1 + tokenBytes;
            } else {
                lines.add(B_AREA_PREFIX + current);
                current.setLength(0);
                currentBytes = 0;
                if (tokenBytes <= B_AREA_BYTE_BUDGET) {
                    current.append(token);
                    currentBytes = tokenBytes;
                } else {
                    splitOversizedToken(token, charset, lines);
                }
            }
        }
        if (currentBytes > 0) {
            lines.add(B_AREA_PREFIX + current);
        }
        return lines;
    }

    /**
     * 空白で区切ったトークン列へ分ける。引用符で囲んだリテラルは内部の空白を含めて1トークンとし、
     * 折り返しで分断しない。二重引用符({@code ''} / {@code ""})はリテラル内のエスケープとして扱う。
     */
    private static List<String> tokenize(String statement) {
        List<String> tokens = new ArrayList<>();
        int n = statement.length();
        int i = 0;
        while (i < n) {
            while (i < n && statement.charAt(i) == ' ') {
                i++;
            }
            if (i >= n) {
                break;
            }
            int start = i;
            char quote = 0;
            while (i < n) {
                char c = statement.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        if (i + 1 < n && statement.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        quote = 0;
                    }
                    i++;
                    continue;
                }
                if (c == ' ') {
                    break;
                }
                if (c == '\'' || c == '"') {
                    quote = c;
                }
                i++;
            }
            tokens.add(statement.substring(start, i));
        }
        return tokens;
    }

    private void splitOversizedToken(String token, Charset charset, List<String> lines) {
        if (token.charAt(0) == '\'' || token.charAt(0) == '"') {
            splitLiteral(token, charset, lines);
        } else {
            splitWord(token, charset, lines);
        }
    }

    /** 語を文字境界で継続行へ途中分割する。先頭の物理行は語境界起点なので継続指示を置かない。 */
    private static void splitWord(String word, Charset charset, List<String> lines) {
        StringBuilder piece = new StringBuilder();
        int pieceBytes = 0;
        boolean first = true;
        int i = 0;
        while (i < word.length()) {
            int codePoint = word.codePointAt(i);
            String unit = new String(Character.toChars(codePoint));
            int unitBytes = byteLength(unit, charset);
            if (pieceBytes > 0 && pieceBytes + unitBytes > B_AREA_BYTE_BUDGET) {
                lines.add((first ? B_AREA_PREFIX : CONTINUATION_PREFIX) + piece);
                first = false;
                piece.setLength(0);
                pieceBytes = 0;
            }
            piece.append(unit);
            pieceBytes += unitBytes;
            i += Character.charCount(codePoint);
        }
        lines.add((first ? B_AREA_PREFIX : CONTINUATION_PREFIX) + piece);
    }

    /**
     * リテラルを継続行へ途中分割する。継続行のB領域先頭へ開き引用符を再挿入し、分割途中の行は
     * 72桁ちょうどまで埋める。先頭の物理行は語境界起点なので継続指示を置かない。
     */
    private static void splitLiteral(String literal, Charset charset, List<String> lines) {
        String quote = String.valueOf(literal.charAt(0));
        int quoteBytes = byteLength(quote, charset);
        StringBuilder piece = new StringBuilder();
        int pieceBytes = 0;
        boolean first = true;
        int i = 0;
        while (i < literal.length()) {
            int codePoint = literal.codePointAt(i);
            String unit = new String(Character.toChars(codePoint));
            int unitBytes = byteLength(unit, charset);
            if (pieceBytes + unitBytes > B_AREA_BYTE_BUDGET) {
                lines.add((first ? B_AREA_PREFIX : CONTINUATION_PREFIX) + piece);
                first = false;
                piece.setLength(0);
                piece.append(quote);
                pieceBytes = quoteBytes;
            }
            piece.append(unit);
            pieceBytes += unitBytes;
            i += Character.charCount(codePoint);
        }
        lines.add((first ? B_AREA_PREFIX : CONTINUATION_PREFIX) + piece);
    }

    private static int byteLength(String text, Charset charset) {
        return text.getBytes(charset).length;
    }
}

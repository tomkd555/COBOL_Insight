package jp.cobolinsight.core.fix;

import jp.cobolinsight.core.encoding.CodePage;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedFormatNormalizerTest {

    private final FixedFormatNormalizer normalizer = new FixedFormatNormalizer();

    private static int byteLen(String line, Charset charset) {
        return line.getBytes(charset).length;
    }

    @Test
    void insertedStatementStartsAtBAreaColumn12() {
        List<String> lines = normalizer.layoutStatement(
                "IF SQLCODE NOT = 0 DISPLAY 'SQL ERROR: ' SQLCODE END-IF", StandardCharsets.UTF_8);

        assertEquals(1, lines.size());
        String line = lines.get(0);
        // 一連番号欄(1-6桁)・7桁目指示欄・A領域(8-11桁)は空白。
        assertEquals("           ", line.substring(0, 11));
        assertEquals(' ', line.charAt(6));
        // 本文はB領域起点(12桁目=index 11)から始まる。
        assertEquals("IF SQLCODE NOT = 0 DISPLAY 'SQL ERROR: ' SQLCODE END-IF", line.substring(11));
        // 識別欄(73-80桁)へ食い込まない。
        assertTrue(byteLen(line, StandardCharsets.UTF_8) <= 72);
    }

    @Test
    void contentFillingBAreaExactlyStaysOnOneLine() {
        // B領域は12-72桁の61桁。ちょうど61バイトの ASCII は折り返さない。
        String content = "A".repeat(61);
        List<String> lines = normalizer.layoutStatement(content, StandardCharsets.UTF_8);

        assertEquals(1, lines.size());
        assertEquals(72, byteLen(lines.get(0), StandardCharsets.UTF_8));
    }

    @Test
    void reservedTrailingBytesShrinkTheBudgetOfTheFinalLineOnly() {
        // 整形後に呼び出し側が末尾へ1バイト(終止ピリオド)を書き足す前提。最終物理行の予算だけが
        // 60バイトへ縮み、それより前の行は61バイトのまま使い切る。
        String statement = "A".repeat(55) + " " + "B".repeat(5);
        assertEquals(61, byteLen(statement, StandardCharsets.UTF_8));

        List<String> withoutReserve =
                normalizer.layoutStatement(statement, StandardCharsets.UTF_8);
        assertEquals(1, withoutReserve.size());
        assertEquals(72, byteLen(withoutReserve.get(0), StandardCharsets.UTF_8));

        List<String> reserved = normalizer.layoutStatement(statement, StandardCharsets.UTF_8, 1);
        assertEquals(2, reserved.size());
        assertEquals("           " + "A".repeat(55), reserved.get(0));
        assertEquals("           " + "B".repeat(5), reserved.get(1));
        // 末尾へピリオドを書き足しても72桁を超えない。
        assertTrue(byteLen(reserved.get(1) + ".", StandardCharsets.UTF_8) <= 72);
    }

    @Test
    void reservedTrailingBytesDoNotShrinkLinesBeforeTheFinalOne() {
        String statement = "A".repeat(61) + " " + "B".repeat(5);

        List<String> lines = normalizer.layoutStatement(statement, StandardCharsets.UTF_8, 1);

        assertEquals(2, lines.size());
        // 最終行でない1行目はB領域を61バイト使い切る。
        assertEquals(72, byteLen(lines.get(0), StandardCharsets.UTF_8));
        assertEquals("           " + "B".repeat(5), lines.get(1));
    }

    @Test
    void asciiOverflowWrapsToContinuationLine() {
        // 62バイトは61桁のB領域に収まらず、継続行(7桁目に '-')へ折り返す。
        String content = "A".repeat(62);
        List<String> lines = normalizer.layoutStatement(content, StandardCharsets.UTF_8);

        assertEquals(2, lines.size());
        assertEquals("           " + "A".repeat(61), lines.get(0));
        assertEquals('-', lines.get(1).charAt(6));
        assertEquals("      -    " + "A", lines.get(1));
        for (String line : lines) {
            assertTrue(byteLen(line, StandardCharsets.UTF_8) <= 72);
        }
    }

    @Test
    void fullWidthOverflowWrapsAtCharBoundaryWithoutSplittingBytes() {
        // Shift_JIS の全角は2バイト。B領域61バイトには30文字(60バイト)まで収まり、31文字目で折り返す。
        Charset sjis = CodePage.SHIFT_JIS.charset();
        String content = "あ".repeat(31);
        List<String> lines = normalizer.layoutStatement(content, sjis);

        assertEquals(2, lines.size());
        // 1行目のB領域は30文字ちょうど(全角を分断しない)。
        assertEquals("あ".repeat(30), lines.get(0).substring(11));
        assertEquals("あ", lines.get(1).substring(11));
        for (String line : lines) {
            assertTrue(byteLen(line, sjis) <= 72, "各行は72バイト以下: " + line);
            // 復号し直しても文字化けしない=文字境界で折り返している。
            assertEquals(line, new String(line.getBytes(sjis), sjis));
        }
    }

    @Test
    void multiLineWrapCoversAllContentInOrder() {
        String content = "B".repeat(130);
        List<String> lines = normalizer.layoutStatement(content, StandardCharsets.UTF_8);

        assertEquals(3, lines.size());
        String rejoined = lines.stream()
                .map(line -> line.substring(11))
                .reduce("", String::concat);
        assertEquals(content, rejoined);
        assertEquals(' ', lines.get(0).charAt(6));
        assertEquals('-', lines.get(1).charAt(6));
        assertEquals('-', lines.get(2).charAt(6));
    }

    @Test
    void multiWordStatementFoldsAtWordBoundaryWithoutHyphen() {
        // R017(ファイル状態未検査)の修正案が挿入する検査文。81バイトで B領域予算(61バイト)を
        // 超え、語境界で折り返す。
        String handler =
                "IF WS-ORDIN-STATUS NOT = '00' DISPLAY 'FILE ERROR: ORDIN ' WS-ORDIN-STATUS END-IF";
        assertEquals(81, byteLen(handler, StandardCharsets.UTF_8));

        List<String> lines = normalizer.layoutStatement(handler, StandardCharsets.UTF_8);

        assertEquals(2, lines.size());
        for (String line : lines) {
            // 語境界での折り返しは継続指示 '-' を置かず、7桁目は空白のまま。
            assertEquals(' ', line.charAt(6), "語境界の折り返しに '-' を置かない: " + line);
            // 一連番号欄(1-6桁)・7桁目指示欄・A領域(8-11桁)は空白、本文は12桁目から。
            assertEquals("           ", line.substring(0, 11));
            assertTrue(byteLen(line, StandardCharsets.UTF_8) <= 72, "各行は72バイト以下: " + line);
        }
        // 改行を空白区切りとして復元すると原文へ戻る(トークンが結合していない)。
        String rejoined = lines.stream()
                .map(line -> line.substring(11))
                .reduce((a, b) -> a + " " + b)
                .orElseThrow();
        assertEquals(handler, rejoined);
        // 内部に空白を含むリテラルは分断されず1行に温存される。
        assertTrue(lines.get(0).contains("'FILE ERROR: ORDIN '"),
                "空白を含むリテラルは分断しない: " + lines.get(0));
    }

    @Test
    void plainWordsFoldAtSpaceKeepTokensSeparate() {
        // 語が連なり B領域予算を超える文。語境界で折り、隣接語が結合しないことを確認する。
        String statement =
                "MOVE AAAAAAAAAA TO BBBBBBBBBB MOVE CCCCCCCCCC TO DDDDDDDDDD MOVE EEEE TO FFFF";
        assertTrue(byteLen(statement, StandardCharsets.UTF_8) > 61);

        List<String> lines = normalizer.layoutStatement(statement, StandardCharsets.UTF_8);

        assertTrue(lines.size() >= 2, "予算超過で複数行へ折る");
        for (String line : lines) {
            assertEquals(' ', line.charAt(6), "語境界の折り返しに '-' を置かない: " + line);
            assertTrue(byteLen(line, StandardCharsets.UTF_8) <= 72);
        }
        String rejoined = lines.stream()
                .map(line -> line.substring(11))
                .reduce((a, b) -> a + " " + b)
                .orElseThrow();
        assertEquals(statement, rejoined);
    }

    @Test
    void oversizedLiteralSplitsWithReinsertedOpeningQuote() {
        // 1トークンのリテラルが B領域予算を超える場合のみ、継続行で途中分割する。
        String literal = "'" + "X".repeat(130) + "'";
        List<String> lines = normalizer.layoutStatement(literal, StandardCharsets.UTF_8);

        assertEquals(3, lines.size());
        // 先頭行は語境界起点なので '-' 無し、以降の継続行は '-' を置く。
        assertEquals(' ', lines.get(0).charAt(6));
        assertEquals('-', lines.get(1).charAt(6));
        assertEquals('-', lines.get(2).charAt(6));
        // 分割途中の行は72桁ちょうどまで埋める(短いと行末の空白がリテラルへ混入するため)。
        assertEquals(72, byteLen(lines.get(0), StandardCharsets.UTF_8));
        assertEquals(72, byteLen(lines.get(1), StandardCharsets.UTF_8));
        assertTrue(byteLen(lines.get(2), StandardCharsets.UTF_8) <= 72);
        // 継続行の本文は再挿入した開き引用符から始まる。
        assertEquals('\'', lines.get(1).charAt(11));
        assertEquals('\'', lines.get(2).charAt(11));

        // COBOL 継続規則で復元すると原リテラルへ戻る(引用符の再挿入で破損しない)。
        StringBuilder body = new StringBuilder();
        for (int k = 0; k < lines.size(); k++) {
            String content = lines.get(k).substring(11).substring(1); // 先頭の引用符を除く
            if (k == lines.size() - 1) {
                content = content.substring(0, content.length() - 1); // 末尾の閉じ引用符を除く
            }
            body.append(content);
        }
        assertEquals(literal, "'" + body + "'");
    }
}

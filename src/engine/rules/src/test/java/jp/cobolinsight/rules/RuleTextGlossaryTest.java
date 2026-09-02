package jp.cobolinsight.rules;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Keeps every user-visible rule string on the vocabulary of docs/rule-text.md. The scan reads the
 * sources as text because finding messages, code-flow labels and fix descriptions are composed
 * inside evaluate(), and most rules never fire on the samples; a runtime scan would miss them.
 * Comments and Javadoc are English, so a Japanese term found anywhere in a scanned file is user
 * text. The app module's cli and pipeline packages are scanned as well: their stderr lines and
 * JSON error fields reach the GUI unchanged.
 */
class RuleTextGlossaryTest {

    /** A wording the glossary replaces, and what to write instead. */
    private record Ban(Pattern pattern, String instead) {
        static Ban word(String text, String instead) {
            return new Ban(Pattern.compile(Pattern.quote(text)), instead);
        }
    }

    private static final List<Path> ROOTS = List.of(
            Path.of("src/main/java/jp/cobolinsight/rules"),
            Path.of("../app/src/main/java/jp/cobolinsight/app/cli"),
            Path.of("../app/src/main/java/jp/cobolinsight/app/pipeline"));

    private static final List<Ban> BANNED = List.of(
            Ban.word("桁落ち", "切り捨て"),
            // 桁 is the column position after a number (8〜72桁, 第 7 桁); a digit count is けた.
            new Ban(Pattern.compile("(?<![0-9０-９第〜]|[0-9] )桁"), "けた (digits) / N桁 (column)"),
            Ban.word("図形定数", "表意定数"),
            Ban.word("参照修飾", "部分参照"),
            Ban.word("送信項目", "送り出し側項目"),
            Ban.word("受信項目", "受け取り側項目"),
            Ban.word("移送", "転記"),
            Ban.word("流下", "次の段落へ制御が移る"),
            Ban.word("届く", "到達する"),
            Ban.word("届か", "到達し"),
            Ban.word("離れ", "移"),
            Ban.word("パラグラフ", "段落"),
            Ban.word("セクション", "節"),
            Ban.word("リテラル", "定数"),
            Ban.word("ファイル状態", "入出力状態"),
            Ban.word("判定", "検査"),
            Ban.word("指摘し", "検出し"),
            Ban.word("汚染", "外部入力に由来する値"),
            Ban.word("変数", "データ項目"),
            Ban.word("オーバーフロー", "あふれ"),
            Ban.word("行目", "行"),
            Ban.word("ソース", "原始プログラム"),
            Ban.word("コール", "呼び出し"),
            Ban.word("可能性がある", "〜得ます"),
            Ban.word("条件コード", "戻りコード"),
            // A 常体 sentence ending before 「。」; every field is 敬体 (docs/rule-text.md, Register).
            // ます・です・ました・でした・ください are the 敬体 endings that share a final kana.
            new Ban(Pattern.compile("(?:(?<!ま)(?<!で)す|(?<!まし)(?<!でし)た|(?<!くださ)い|[るだむくつぬぶぐ])。"),
                    "敬体 (〜ます。/〜です。/〜ください。)"));

    /** Phrases that contain a banned substring but are correct as they stand. */
    private static final String[] ALLOWED = {"ホスト変数", "文字定数"};

    @Test
    void ruleTextUsesTheGlossary() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : ROOTS) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    scan(file, violations);
                }
            }
        }
        assertEquals(List.of(), violations, "rule text outside docs/rule-text.md");
    }

    private static void scan(Path file, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String allowed : ALLOWED) {
                line = line.replace(allowed, "");
            }
            for (Ban ban : BANNED) {
                if (ban.pattern().matcher(line).find()) {
                    violations.add(file.getFileName() + ":" + (i + 1) + " "
                            + ban.pattern() + " -> " + ban.instead());
                }
            }
        }
    }
}

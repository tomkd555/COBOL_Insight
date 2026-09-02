package jp.cobolinsight.rules;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Keeps every user-visible rule string on the vocabulary of docs/rule-text.md. The scan reads the
 * rule sources as text because finding messages, code-flow labels and fix descriptions are composed
 * inside evaluate(), and most rules never fire on the samples; a runtime scan would miss them.
 * Comments and Javadoc are English, so a Japanese term found anywhere in a scanned file is rule text.
 */
class RuleTextGlossaryTest {

    /** The packages already rewritten. A package joins the list when its rewrite lands. */
    private static final String[] SCANNED = {"dataflow", "cfg", "syntax", "sql", "custom"};

    /** Wordings the glossary replaces. The term after the arrow is what to write instead. */
    private static final String[][] BANNED = {
            {"桁落ち", "切り捨て"},
            {"桁", "けた"},
            {"図形定数", "表意定数"},
            {"参照修飾", "部分参照"},
            {"送信項目", "送り出し側項目"},
            {"受信項目", "受け取り側項目"},
            {"移送", "転記"},
            {"流下", "次の段落へ制御が移る"},
            {"届く", "到達する"},
            {"届か", "到達し"},
            {"離れ", "移"},
            {"パラグラフ", "段落"},
            {"セクション", "節"},
            {"リテラル", "定数"},
            {"ファイル状態", "入出力状態"},
            {"判定", "検査"},
            {"指摘し", "検出し"},
            {"汚染", "外部入力に由来する値"},
            {"変数", "データ項目"},
            {"オーバーフロー", "あふれ"},
            {"行目", "行"},
            {"ます。", "常体"},
            {"です。", "常体"},
            {"ません", "常体"},
    };

    /** Phrases that contain a banned substring but are correct as they stand. */
    private static final String[] ALLOWED = {"ホスト変数", "文字定数"};

    @Test
    void ruleTextUsesTheGlossary() throws IOException {
        List<String> violations = new ArrayList<>();
        Path root = Path.of("src/main/java/jp/cobolinsight/rules");
        for (String pkg : SCANNED) {
            try (Stream<Path> files = Files.walk(root.resolve(pkg))) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        for (String allowed : ALLOWED) {
                            line = line.replace(allowed, "");
                        }
                        for (String[] entry : BANNED) {
                            if (line.contains(entry[0])) {
                                violations.add(file.getFileName() + ":" + (i + 1) + " "
                                        + entry[0] + " -> " + entry[1]);
                            }
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), violations, "rule text outside docs/rule-text.md");
    }
}

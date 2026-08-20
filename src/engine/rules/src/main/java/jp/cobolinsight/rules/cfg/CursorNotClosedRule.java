package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlock;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlockKind;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R019 カーソルクローズ漏れ。EXEC SQL の DECLARE CURSOR・OPEN・CLOSE を突合し、DECLARE かつ
 * OPEN されているが CLOSE されないカーソルを検出する。CLOSE 漏れは接続資源を保持し続ける。
 */
public final class CursorNotClosedRule implements Rule {

    private static final Pattern DECLARE =
            Pattern.compile("(?i)DECLARE\\s+([\\p{L}\\p{N}_-]+)\\s+CURSOR");
    private static final Pattern OPEN = Pattern.compile("(?i)^\\s*OPEN\\s+([\\p{L}\\p{N}_-]+)");
    private static final Pattern CLOSE = Pattern.compile("(?i)^\\s*CLOSE\\s+([\\p{L}\\p{N}_-]+)");

    @Override
    public String id() {
        return "R019";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("SQLカーソルのCLOSE漏れ", "SQL")
                .summary("DECLARE して OPEN したが CLOSE していないカーソルを検出します。")
                .rationale("接続資源とロックを保持し続けるため、"
                        + "同時実行数の多い環境で資源の枯渇と待ちを招きます。")
                .detection("EXEC SQL の DECLARE CURSOR・OPEN・CLOSE をカーソル名で突き合わせ、"
                        + "OPEN があり CLOSE の無いものを検出します。")
                .remedy("処理の終わりと異常時の経路の双方で CLOSE を実行します。")
                .example("""
                        EXEC SQL OPEN CUR-CUST END-EXEC.
                        PERFORM FETCH-LOOP UNTIL WS-EOF = "Y".
                        GOBACK.
                        """, """
                        EXEC SQL OPEN CUR-CUST END-EXEC.
                        PERFORM FETCH-LOOP UNTIL WS-EOF = "Y".
                        EXEC SQL CLOSE CUR-CUST END-EXEC.
                        """)
                .build();
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.MEDIUM;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.CONTROL_FLOW;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            Set<String> declared = new LinkedHashSet<>();
            Map<String, EmbeddedBlock> openedAt = new LinkedHashMap<>();
            Set<String> closed = new LinkedHashSet<>();
            for (EmbeddedBlock block : model.embeddedBlocks()) {
                if (block.kind() != EmbeddedBlockKind.SQL) {
                    continue;
                }
                String body = sqlBody(block.text());
                Matcher declare = DECLARE.matcher(body);
                if (declare.find()) {
                    declared.add(key(declare.group(1)));
                    continue;
                }
                Matcher open = OPEN.matcher(body);
                if (open.find()) {
                    openedAt.putIfAbsent(key(open.group(1)), block);
                    continue;
                }
                Matcher close = CLOSE.matcher(body);
                if (close.find()) {
                    closed.add(key(close.group(1)));
                }
            }
            for (Map.Entry<String, EmbeddedBlock> entry : openedAt.entrySet()) {
                String cursor = entry.getKey();
                if (declared.contains(cursor) && !closed.contains(cursor)) {
                    findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                            "カーソル " + cursor + " は DECLARE・OPEN されているが CLOSE されない。",
                            new SourcePosition(model.sourceFile(),
                                    entry.getValue().range().start().line(), 1,
                                    SourcePosition.UNKNOWN_BYTE_OFFSET)));
                }
            }
        }
        return findings;
    }

    /** EXEC SQL を取り除き、単一空白へ整形した本体。行頭一致の OPEN/CLOSE 判定に使う。 */
    private static String sqlBody(String blockText) {
        String normalized = blockText.replaceAll("\\s+", " ").trim();
        int index = normalized.toUpperCase(Locale.ROOT).indexOf("EXEC SQL");
        return index < 0 ? normalized
                : normalized.substring(index + "EXEC SQL".length()).trim();
    }

    private static String key(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}

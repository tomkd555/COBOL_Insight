package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

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
 * R019 Missing cursor close. Cross-references EXEC SQL's DECLARE CURSOR, OPEN, and CLOSE to
 * detect cursors that are both DECLAREd and OPENed but never CLOSEd. A missing CLOSE keeps
 * holding onto connection resources.
 */
public final class CursorNotClosedRule implements Rule {

    private static final Pattern DECLARE =
            Pattern.compile("(?i)DECLARE\\s+([\\p{L}\\p{N}_-]+)\\s+CURSOR");
    private static final Pattern OPEN = Pattern.compile("(?i)^\\s*OPEN\\s+([\\p{L}\\p{N}_-]+)");
    private static final Pattern CLOSE = Pattern.compile("(?i)^\\s*CLOSE\\s+([\\p{L}\\p{N}_-]+)");

    private static final RuleMeta META = RuleMeta.named("R019", "SQL カーソルの CLOSE 漏れ", "SQL")
            .summary("DECLARE して OPEN したが CLOSE しないカーソルを検出します。")
            .rationale("接続資源とロックを保持し続けるため、"
                    + "同時実行数の多い環境で資源の枯渇と待ちを招きます。")
            .detection("EXEC SQL の DECLARE CURSOR・OPEN・CLOSE をカーソル名で突き合わせ、"
                    + "OPEN があり CLOSE のないものを検出します。"
                    + "DECLARE CURSOR のないカーソル名は対象外です。")
            .remedy("処理の終わりと異常時の経路の双方で CLOSE を実行してください。")
            .example("""
                    EXEC SQL OPEN CUR-CUST END-EXEC.
                    PERFORM FETCH-LOOP UNTIL WS-EOF = "Y".
                    GOBACK.
                    """, """
                    EXEC SQL OPEN CUR-CUST END-EXEC.
                    PERFORM FETCH-LOOP UNTIL WS-EOF = "Y".
                    EXEC SQL CLOSE CUR-CUST END-EXEC.
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
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
                    findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                            cursor + " が OPEN のまま CLOSE されていません。",
                            new SourcePosition(model.sourceFile(),
                                    entry.getValue().range().start().line(), 1,
                                    SourcePosition.UNKNOWN_BYTE_OFFSET)));
                }
            }
        }
        return findings;
    }

    /** The body with EXEC SQL stripped off and whitespace collapsed to single spaces. Used for line-start OPEN/CLOSE matching. */
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

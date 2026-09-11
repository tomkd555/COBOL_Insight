package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.CodeFlow;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R039 A COMMIT taken while a cursor without WITH HOLD is open. From each OPEN, walks forward to
 * the CLOSE of the same cursor and looks for a COMMIT or ROLLBACK on the way. Db2 closes a cursor
 * declared without WITH HOLD at the end of the unit of work, so the next FETCH fails with
 * SQLCODE -501. CICS programs are out of scope: there the unit of work ends with SYNCPOINT.
 */
public final class CursorHoldCommitRule implements Rule {

    private static final Pattern DECLARE = Pattern.compile(
            "(?i)DECLARE\\s+([\\p{L}\\p{N}$#@_-]+)\\s+CURSOR\\b");
    private static final Pattern WITH_HOLD = Pattern.compile("(?i)\\bWITH\\s+HOLD\\b");
    private static final Pattern OPEN = Pattern.compile("(?i)^OPEN\\s+([\\p{L}\\p{N}$#@_-]+)");
    private static final Pattern CLOSE = Pattern.compile("(?i)^CLOSE\\s+([\\p{L}\\p{N}$#@_-]+)");
    private static final Pattern END_OF_WORK = Pattern.compile("(?i)^(COMMIT|ROLLBACK)\\b");

    private static final RuleMeta META = RuleMeta
            .named("R039", "WITH HOLD のないカーソルを開いたままの COMMIT", "SQL")
            .summary("WITH HOLD を付けずに宣言したカーソルを開いたまま COMMIT する"
                    + "経路を検出します。")
            .rationale("同期点でカーソルが閉じられるため、"
                    + "後続の FETCH が SQLCODE -501 で失敗します。")
            .detection("OPEN から同じカーソルの CLOSE までの前方経路に COMMIT か"
                    + "ROLLBACK があり、その DECLARE CURSOR に WITH HOLD がないものを"
                    + "検出します。WITH HOLD を付けたカーソルと、"
                    + "EXEC CICS を含むプログラムは対象外です。")
            .remedy("COMMIT をまたいで読み続けるカーソルには WITH HOLD を付けるか、"
                    + "COMMIT のたびに CLOSE と OPEN をやり直してください。")
            .example("""
                    EXEC SQL DECLARE CSR-KEIYAKU CURSOR FOR
                        SELECT KEIYAKU_NO FROM FLDB.KEIYAKU END-EXEC.
                    """, """
                    EXEC SQL DECLARE CSR-KEIYAKU CURSOR WITH HOLD FOR
                        SELECT KEIYAKU_NO FROM FLDB.KEIYAKU END-EXEC.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .needs(Needs.CFG)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        ControlFlowGraphs cfgs = context.artifact(ControlFlowGraphs.class).orElse(null);
        if (cfgs == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            cfgs.of(model).ifPresent(cfg -> evaluate(model, cfg, findings));
        }
        return findings;
    }

    private void evaluate(CobolSemanticModel model, ControlFlowGraph cfg, List<Finding> findings) {
        if (model.embeddedBlocks().stream().anyMatch(block -> block.kind().isCics())) {
            return;
        }
        Set<String> heldCursors = new LinkedHashSet<>();
        Map<String, EmbeddedBlock> openedAt = new HashMap<>();
        Set<String> declared = new LinkedHashSet<>();
        for (EmbeddedBlock block : sqlBlocks(model)) {
            String body = body(block.text());
            Matcher declare = DECLARE.matcher(body);
            if (declare.find()) {
                String cursor = declare.group(1).toUpperCase(Locale.ROOT);
                declared.add(cursor);
                if (WITH_HOLD.matcher(body).find()) {
                    heldCursors.add(cursor);
                }
                continue;
            }
            Matcher open = OPEN.matcher(body);
            if (open.find()) {
                openedAt.putIfAbsent(open.group(1).toUpperCase(Locale.ROOT), block);
            }
        }
        Map<SourceRange, CfgNode> byRange = execSqlNodes(cfg);
        for (Map.Entry<String, EmbeddedBlock> entry : openedAt.entrySet()) {
            String cursor = entry.getKey();
            if (!declared.contains(cursor) || heldCursors.contains(cursor)) {
                continue;
            }
            CfgNode start = byRange.get(entry.getValue().range());
            if (start == null) {
                continue;
            }
            CfgNode commit = CfgSupport.firstMatch(cfg, start,
                    node -> matches(node, CLOSE, cursor),
                    node -> textOf(node).map(body -> END_OF_WORK.matcher(body).find()).orElse(false))
                    .orElse(null);
            if (commit == null) {
                continue;
            }
            int openLine = entry.getValue().range().start().line();
            int commitLine = commit.statement().orElseThrow().range().start().line();
            findings.add(new Finding(META.id(), META.defaultSeverity().toLevel(),
                    cursor + " は WITH HOLD なしで宣言されています。" + commitLine
                            + "行の同期点でカーソルが閉じられ、後続の FETCH が失敗します。",
                    new SourcePosition(model.sourceFile(), openLine, 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET),
                    List.of(new CodeFlow(List.of(
                            CfgSupport.step(model.sourceFile(), openLine,
                                    "カーソルの OPEN（WITH HOLD なし）"),
                            CfgSupport.step(model.sourceFile(), commitLine,
                                    "同期点（ここでカーソルが閉じられる）")))),
                    List.of()));
        }
    }

    /** The EXEC SQL statement nodes of the graph, indexed by the range their embedded block carries. */
    private static Map<SourceRange, CfgNode> execSqlNodes(ControlFlowGraph cfg) {
        Map<SourceRange, CfgNode> byRange = new HashMap<>();
        for (CfgNode node : cfg.nodes()) {
            node.statement().ifPresent(statement -> {
                if (statement instanceof SimpleStatement simple
                        && "EXEC SQL".equals(simple.verb())) {
                    byRange.put(simple.range(), node);
                }
            });
        }
        return byRange;
    }

    private static boolean matches(CfgNode node, Pattern pattern, String cursor) {
        return textOf(node).map(body -> {
            Matcher matcher = pattern.matcher(body);
            return matcher.find() && matcher.group(1).toUpperCase(Locale.ROOT).equals(cursor);
        }).orElse(false);
    }

    private static Optional<String> textOf(CfgNode node) {
        return node.statement()
                .filter(SimpleStatement.class::isInstance)
                .map(SimpleStatement.class::cast)
                .filter(simple -> "EXEC SQL".equals(simple.verb()))
                .map(simple -> body(simple.text()));
    }

    private static List<EmbeddedBlock> sqlBlocks(CobolSemanticModel model) {
        return model.embeddedBlocks().stream()
                .filter(block -> block.kind() == EmbeddedBlockKind.SQL).toList();
    }

    /** The statement with the EXEC SQL wrapper stripped and its layout collapsed to single spaces. */
    private static String body(String blockText) {
        String text = blockText.replaceAll("\\s+", " ").trim();
        int exec = text.toUpperCase(Locale.ROOT).indexOf("EXEC SQL");
        return exec < 0 ? text : text.substring(exec + "EXEC SQL".length()).trim();
    }
}

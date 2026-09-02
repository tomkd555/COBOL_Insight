package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.CodeFlow;
import jp.cobolinsight.core.finding.CodeFlowStep;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FixSuggestion;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.FixProducer;
import jp.cobolinsight.rules.FixEdits;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * R018 Unchecked SQLCODE. After a data-changing DML (INSERT/UPDATE/DELETE) EXEC SQL executes,
 * detects points on the forward path up to the next EXEC SQL where SQLCODE/SQLSTATE is never
 * referenced in a condition. Without a check, subsequent processing continues without detecting
 * an update failure. Because SQLCODE gets overwritten by the next SQL statement, the boundary is
 * set at the next EXEC SQL statement. SELECT INTO and FETCH are out of scope.
 */
public final class SqlCodeUncheckedRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("R018", "SQLCODE・SQLSTATE 未検査", "例外処理")
            .summary("INSERT・UPDATE・DELETE の後、次の埋込みSQL文までに"
                    + "SQLCODE・SQLSTATE を検査しない箇所を検出します。")
            .rationale("更新の失敗を検知せずに後続が進み、"
                    + "更新されたつもりのデータで処理を続けます。")
            .detection("データを変更する DML の実行後、次の埋込みSQL文に達するまでの前方経路で"
                    + "SQLCODE・SQLSTATE を条件で参照しないものを検出します。"
                    + "SELECT INTO・FETCH は対象外です。")
            .remedy("DML の直後に SQLCODE を検査し、0 以外を異常として処理してください。")
            .example("""
                    EXEC SQL UPDATE CUSTOMER SET NAME = :WS-NAME
                             WHERE ID = :WS-ID END-EXEC.
                    PERFORM NEXT-SHORI.
                    """, """
                    EXEC SQL UPDATE CUSTOMER SET NAME = :WS-NAME
                             WHERE ID = :WS-ID END-EXEC.
                    IF SQLCODE NOT = ZERO
                        PERFORM SQL-ERROR
                    END-IF.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT, Command.REPORT, Command.FIX)
            .targets(AssetKind.COBOL)
            .needs(Needs.SEMANTIC, Needs.CFG, Needs.SOURCE_TEXT)
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
        // Build the set of EXEC SQL statement nodes (all SQL; used as boundaries) and a range->node index.
        Set<CfgNode> execSqlNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<SourceRange, CfgNode> byRange = new HashMap<>();
        for (CfgNode node : cfg.nodes()) {
            node.statement().ifPresent(statement -> {
                if (statement instanceof SimpleStatement simple
                        && "EXEC SQL".equals(simple.verb())) {
                    execSqlNodes.add(node);
                    byRange.put(simple.range(), node);
                }
            });
        }
        for (EmbeddedBlock block : model.embeddedBlocks()) {
            if (block.kind() != EmbeddedBlockKind.SQL) {
                continue;
            }
            String keyword = leadingSqlKeyword(block.text());
            if (!keyword.equals("INSERT") && !keyword.equals("UPDATE")
                    && !keyword.equals("DELETE")) {
                continue;
            }
            CfgNode start = byRange.get(block.range());
            if (start == null) {
                continue;
            }
            boolean checked = CfgSupport.forwardHasMatch(cfg, start,
                    execSqlNodes::contains, SqlCodeUncheckedRule::referencesSqlCode);
            if (!checked) {
                String file = model.sourceFile();
                int first = block.range().start().line();
                int last = block.range().end().line();
                String dml = keyword + " " + targetTable(block.text(), keyword);
                CfgNode next = CfgSupport.firstBoundary(cfg, start, execSqlNodes::contains)
                        .orElse(null);
                Integer nextLine = next == null ? null
                        : next.statement().orElseThrow().range().start().line();
                String until = nextLine == null ? "プログラムの終端まで進む"
                        : nextLine <= last ? "ループで " + nextLine + "行の SQL へ戻る"
                        : "次の SQL（" + nextLine + "行）へ進む";
                List<CodeFlowStep> steps = new ArrayList<>();
                steps.add(CfgSupport.step(file, first,
                        dml + " の実行（SQLCODE が設定される）"));
                if (nextLine != null) {
                    steps.add(CfgSupport.step(file, nextLine, (nextLine <= last
                            ? "ループで戻る SQL（" : "次の SQL（")
                            + "SQLCODE が上書きされる）"));
                }
                findings.add(new Finding(META.id(), META.defaultSeverity().toLevel(),
                        "SQLCODE を EXEC SQL " + dml + " の後で検査していません。"
                                + until + "ため、更新の失敗が検知されません。",
                        new SourcePosition(file, last, 1, SourcePosition.UNKNOWN_BYTE_OFFSET),
                        List.of(new CodeFlow(steps)), List.of()));
            }
        }
    }

    @Override
    public Optional<FixProducer> fix() {
        return Optional.of(new SqlCodeFixProducer());
    }

    private static boolean isDataChangeDml(String keyword) {
        return keyword.equals("INSERT") || keyword.equals("UPDATE") || keyword.equals("DELETE");
    }

    /**
     * Inserts an SQLCODE check statement right after an unchecked data-changing DML's EXEC SQL
     * (on the line after the END-EXEC line). Re-identifies the DML block with the same terminal
     * line using Finding.location's line (= the END-EXEC line) as the anchor. The inserted IF is
     * always closed with an explicit END-IF, and a terminating period is added only when
     * END-EXEC itself closes a sentence.
     */
    private static final class SqlCodeFixProducer implements FixProducer {

        @Override
        public Optional<FixSuggestion> produce(Finding finding, AnalysisContext context) {
            if (!"R018".equals(finding.ruleId())) {
                return Optional.empty();
            }
            String file = finding.location().file();
            int line = finding.location().line();
            CobolSemanticModel model = FixEdits.modelOf(context, file).orElse(null);
            if (model == null) {
                return Optional.empty();
            }
            EmbeddedBlock block = model.embeddedBlocks().stream()
                    .filter(candidate -> candidate.kind() == EmbeddedBlockKind.SQL)
                    .filter(candidate -> candidate.range().end().line() == line)
                    .filter(candidate -> isDataChangeDml(leadingSqlKeyword(candidate.text())))
                    .findFirst()
                    .orElse(null);
            if (block == null) {
                return Optional.empty();
            }
            // Only close the inserted IF with a terminating period when END-EXEC itself closes a
            // sentence with one. Placing a period right after an EXEC SQL that sits in the middle
            // of an enclosing statement (IF/PERFORM, etc.) would prematurely terminate the outer
            // statement, so in that case close with only an explicit END-IF.
            String source = context.artifact(SourceTextIndex.class)
                    .flatMap(index -> index.textOf(model.sourceFile())).orElse(null);
            String terminator = source == null
                    || FixEdits.endsSentence(source, block.range().end().line()) ? "." : "";
            TextEdit edit = FixEdits.insertStatementAfter(block.range(),
                    "IF SQLCODE NOT = 0 DISPLAY 'SQL ERROR: ' SQLCODE END-IF" + terminator);
            return Optional.of(new FixSuggestion("SQLCODE の検査を挿入します", List.of(edit)));
        }
    }

    private static boolean referencesSqlCode(CfgNode node) {
        return node.statement()
                .map(statement -> statement instanceof CompoundStatement compound
                        && containsSqlCode(compound.conditionText()))
                .orElse(false);
    }

    private static boolean containsSqlCode(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        return upper.contains("SQLCODE") || upper.contains("SQLSTATE");
    }

    /** The leading SQL keyword (uppercase), with EXEC SQL stripped off. */
    private static String leadingSqlKeyword(String blockText) {
        String normalized = blockText.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT).trim();
        int index = normalized.indexOf("EXEC SQL");
        String body = index < 0 ? normalized
                : normalized.substring(index + "EXEC SQL".length()).trim();
        String[] words = body.split(" ", 2);
        return words.length == 0 ? "" : words[0];
    }

    private static String targetTable(String blockText, String keyword) {
        String normalized = blockText.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        String marker = switch (keyword) {
            case "INSERT" -> "INTO ";
            case "DELETE" -> "FROM ";
            default -> "UPDATE ";
        };
        int at = normalized.indexOf(marker);
        if (at < 0) {
            return "";
        }
        String rest = normalized.substring(at + marker.length()).trim();
        String[] words = rest.split("[ (]", 2);
        return words.length == 0 ? "" : words[0];
    }
}

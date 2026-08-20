package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraphs;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FixSuggestion;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.finding.TextEdit;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.CompoundStatement;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlock;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlockKind;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.source.SourceRange;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.FixProducer;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;
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
 * R018 SQLCODE未検査。データ変更DML(INSERT/UPDATE/DELETE)の EXEC SQL 実行後、次の EXEC SQL に
 * 達するまでの前方経路で SQLCODE・SQLSTATE を条件参照しない箇所を検出する。検査を欠くと、更新の
 * 失敗を検知せずに後続処理が続く。SQLCODE は次の SQL で上書きされるため、境界は次の EXEC SQL 文
 * とする。SELECT INTO・FETCH は対象外。
 */
public final class SqlCodeUncheckedRule implements Rule {

    @Override
    public String id() {
        return "R018";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("SQLCODE/SQLSTATE未検査", "例外処理")
                .summary("INSERT・UPDATE・DELETE の後、次の EXEC SQL までに"
                        + "SQLCODE・SQLSTATE を検査しない箇所を検出します。")
                .rationale("更新の失敗を検知せずに後続が進み、"
                        + "更新されたつもりのデータで処理を続けてしまいます。")
                .detection("データ変更 DML の実行後、次の EXEC SQL に達するまでの前方経路で"
                        + "SQLCODE・SQLSTATE を条件参照しないものを検出します。境界を次の EXEC SQL と"
                        + "するのは、SQLCODE が次の SQL で上書きされるためです。"
                        + "SELECT INTO・FETCH は対象外とします。")
                .remedy("DML の直後に SQLCODE を判定し、0 以外を異常として処理します。")
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
                .build();
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.HIGH;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.CONTROL_FLOW;
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
        // EXEC SQL 文ノード(全SQL。境界に使う)と、range→ノードの索引を作る。
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
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                        "EXEC SQL " + keyword + " " + targetTable(block.text(), keyword)
                                + " の実行後、SQLCODE・SQLSTATE を検査していない。"
                                + "更新が失敗しても後続処理が継続する。",
                        new SourcePosition(model.sourceFile(), block.range().end().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    @Override
    public Optional<FixProducer> fixProducer() {
        return Optional.of(new SqlCodeFixProducer());
    }

    private static boolean isDataChangeDml(String keyword) {
        return keyword.equals("INSERT") || keyword.equals("UPDATE") || keyword.equals("DELETE");
    }

    /**
     * 未検査のデータ変更 DML の EXEC SQL 直後(END-EXEC 行の次行)へ SQLCODE 判定文を挿入する。
     * Finding.location の行(=END-EXEC 行)を anchor に、同一終端行の DML ブロックを再同定する。
     * 挿入する IF は常に明示的な END-IF で閉じ、終止ピリオドは END-EXEC が文を閉じている場合に
     * のみ付ける。
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
            // END-EXEC が終止ピリオドで文を閉じているときだけ、挿入する IF も終止ピリオドで閉じる。
            // 囲む文(IF/PERFORM など)の途中にある EXEC SQL の直後へピリオドを置くと外側の文を
            // 途中で終止させるため、その場合は明示的な END-IF だけで閉じる。
            String source = context.artifact(SourceTextIndex.class)
                    .flatMap(index -> index.textOf(model.sourceFile())).orElse(null);
            String terminator = source == null
                    || FixEdits.endsSentence(source, block.range().end().line()) ? "." : "";
            TextEdit edit = FixEdits.insertStatementAfter(block.range(),
                    "IF SQLCODE NOT = 0 DISPLAY 'SQL ERROR: ' SQLCODE END-IF" + terminator);
            return Optional.of(new FixSuggestion("SQLCODE 検査を挿入する", List.of(edit)));
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

    /** EXEC SQL を除いた先頭のSQLキーワード(大文字)。 */
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

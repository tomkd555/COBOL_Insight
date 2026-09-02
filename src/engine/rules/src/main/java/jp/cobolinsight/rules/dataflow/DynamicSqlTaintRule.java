package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.dataflow.DataFlowFacts;
import jp.cobolinsight.core.dataflow.ProgramDataFlow;
import jp.cobolinsight.core.dataflow.TaintKind;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R020 Unvalidated inclusion of external input into dynamic SQL. Detects, via taint tracking,
 * places where the SQL string operand (host variable) of EXECUTE IMMEDIATE / PREPARE contains a
 * variable tainted by external input such as a screen or report, without validation or
 * substitution. Taint originating from system registers (ACCEPT ... FROM DATE / TIME / DAY, etc.)
 * is excluded as a false positive.
 */
public final class DynamicSqlTaintRule implements Rule {

    private static final Pattern HOST_VAR = Pattern.compile(":\\s*([\\p{L}\\p{N}$#_-]*\\p{L}[\\p{L}\\p{N}$#_-]*)");
    private static final Pattern DYNAMIC_SQL = Pattern.compile("(?is)\\b(EXECUTE\\s+IMMEDIATE|PREPARE)\\b");
    private static final Pattern ACCEPT_FROM_REGISTER = Pattern.compile(
            "(?is)\\bACCEPT\\s+([\\p{L}\\p{N}$#_-]+)\\s+FROM\\s+"
                    + "(DATE|TIME|DAY|DAY-OF-WEEK|DAY-OF-YEAR|TIMER|WHEN-COMPILED|YYYYMMDD|YYYYDDD)\\b");

    private static final RuleMeta META =
            RuleMeta.named("R020", "動的SQL文への外部入力の未検証組み込み", "SQL")
                    .summary("外部入力に由来する値を、検証も置換もせずに組み立てた文字列として"
                            + "EXECUTE IMMEDIATE・PREPARE に渡す箇所を検出します。")
                    .rationale("入力に SQL の断片を混ぜられると問い合わせの意味が変わり、"
                            + "想定していない参照・更新を許します。")
                    .detection("画面・帳票などの外部入力に由来する値が"
                            + "動的SQL文の文字列に到達するものを検出します。"
                            + "ACCEPT FROM DATE・TIME などシステムレジスタに由来する値は対象外です。")
                    .remedy("値をホスト変数として渡し、SQL 文の組み立てに直接埋め込まないでください。")
                    .example("""
                            STRING "SELECT * FROM CUST WHERE ID='" WS-INPUT "'"
                                DELIMITED BY SIZE INTO WS-SQL.
                            EXEC SQL EXECUTE IMMEDIATE :WS-SQL END-EXEC.
                            """, """
                            EXEC SQL PREPARE STMT FROM :WS-SQL-TEMPLATE END-EXEC.
                            EXEC SQL EXECUTE STMT USING :WS-INPUT END-EXEC.
                            """)
                    .severity(Severity.HIGH)
                    .commands(Command.LINT, Command.REPORT)
                    .targets(AssetKind.COBOL)
                    .needs(Needs.SEMANTIC, Needs.CFG, Needs.DATAFLOW)
                    .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        DataFlowFacts facts = context.artifact(DataFlowFacts.class).orElse(null);
        ControlFlowGraphs cfgs = context.artifact(ControlFlowGraphs.class).orElse(null);
        if (facts == null || cfgs == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            ProgramDataFlow df = facts.of(model).orElse(null);
            ControlFlowGraph cfg = cfgs.of(model).orElse(null);
            if (df != null && cfg != null) {
                evaluate(model, cfg, df, findings);
            }
        }
        return findings;
    }

    private void evaluate(CobolSemanticModel model, ControlFlowGraph cfg, ProgramDataFlow df,
            List<Finding> findings) {
        Set<String> systemRegisters = systemRegisterTargets(cfg);
        for (CfgNode node : cfg.nodes()) {
            Statement statement = node.statement().orElse(null);
            if (!(statement instanceof SimpleStatement simple)
                    || !DYNAMIC_SQL.matcher(simple.text()).find()) {
                continue;
            }
            Set<String> tainted = df.taintedAt(node, TaintKind.EXTERNAL_INPUT);
            Set<String> flagged = new LinkedHashSet<>();
            Matcher m = HOST_VAR.matcher(simple.text());
            while (m.find()) {
                String hv = m.group(1).toUpperCase(Locale.ROOT);
                if (tainted.contains(hv) && !systemRegisters.contains(hv)) {
                    flagged.add(hv);
                }
            }
            if (!flagged.isEmpty()) {
                SourcePosition position = new SourcePosition(model.sourceFile(),
                        simple.range().start().line(), 1, SourcePosition.UNKNOWN_BYTE_OFFSET);
                findings.add(new Finding(META.id(), META.defaultSeverity().toLevel(),
                        String.join(", ", flagged)
                                + " を検証せずに動的SQL文へ組み込んでいます。"
                                + "SQL インジェクションになり得ます。",
                        position,
                        TaintCodeFlows.of(model, df, node, TaintKind.EXTERNAL_INPUT, flagged,
                                position, "動的SQL文の文字列に組み込む"),
                        List.of()));
            }
        }
    }

    private static Set<String> systemRegisterTargets(ControlFlowGraph cfg) {
        Set<String> targets = new LinkedHashSet<>();
        for (CfgNode node : cfg.nodes()) {
            node.statement().filter(SimpleStatement.class::isInstance)
                    .map(SimpleStatement.class::cast)
                    .ifPresent(simple -> {
                        Matcher m = ACCEPT_FROM_REGISTER.matcher(simple.text());
                        while (m.find()) {
                            targets.add(m.group(1).toUpperCase(Locale.ROOT));
                        }
                    });
        }
        return targets;
    }
}

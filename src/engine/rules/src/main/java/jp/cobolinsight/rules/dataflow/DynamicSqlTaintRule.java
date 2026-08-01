package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraphs;
import jp.cobolinsight.engineapi.dataflow.DataFlowFacts;
import jp.cobolinsight.engineapi.dataflow.ProgramDataFlow;
import jp.cobolinsight.engineapi.dataflow.TaintKind;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R020 動的SQLへの外部入力の未検証組込。EXECUTE IMMEDIATE / PREPARE の SQL 文字列オペランド(ホスト
 * 変数)が、画面・帳票などの外部入力で汚染された変数を検証・置換なしに含む箇所を汚染追跡で検出する。
 * システムレジスタ由来(ACCEPT ... FROM DATE / TIME / DAY 等)の汚染は偽陽性として除外する。
 */
public final class DynamicSqlTaintRule implements Rule {

    private static final Pattern HOST_VAR = Pattern.compile(":\\s*([\\p{L}\\p{N}$#_-]*\\p{L}[\\p{L}\\p{N}$#_-]*)");
    private static final Pattern DYNAMIC_SQL = Pattern.compile("(?is)\\b(EXECUTE\\s+IMMEDIATE|PREPARE)\\b");
    private static final Pattern ACCEPT_FROM_REGISTER = Pattern.compile(
            "(?is)\\bACCEPT\\s+([\\p{L}\\p{N}$#_-]+)\\s+FROM\\s+"
                    + "(DATE|TIME|DAY|DAY-OF-WEEK|DAY-OF-YEAR|TIMER|WHEN-COMPILED|YYYYMMDD|YYYYDDD)\\b");

    @Override
    public String id() {
        return "R020";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("動的SQL文への外部入力の未検証組み込み", "SQL")
                .summary("外部入力で汚染された値を、検証も置換もせずに組み立てた文字列を"
                        + "EXECUTE IMMEDIATE・PREPARE へ渡す箇所を検出する。")
                .rationale("入力に SQL の断片を混ぜられると問い合わせの意味が変わり、"
                        + "想定していない参照・更新を許す。")
                .detection("画面・帳票などの外部入力を汚染源として汚染追跡を行い、"
                        + "汚染された変数が動的 SQL の文字列オペランドへ届くものを検出する。"
                        + "ACCEPT FROM DATE・TIME などシステムレジスタ由来の汚染は対象外とする。")
                .remedy("値をホスト変数として渡し、SQL 文の組み立てへ直接埋め込まない。")
                .example("""
                        STRING "SELECT * FROM CUST WHERE ID='" WS-INPUT "'"
                            DELIMITED BY SIZE INTO WS-SQL.
                        EXEC SQL EXECUTE IMMEDIATE :WS-SQL END-EXEC.
                        """, """
                        EXEC SQL PREPARE STMT FROM :WS-SQL-TEMPLATE END-EXEC.
                        EXEC SQL EXECUTE STMT USING :WS-INPUT END-EXEC.
                        """)
                .build();
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.HIGH;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.DATA_FLOW;
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
                findings.add(new Finding(id(), defaultSeverity().toLevel(),
                        "動的SQLの文字列に外部入力由来の未検証変数 " + String.join(", ", flagged)
                                + " を組み込んでいる。SQLインジェクションになり得る。",
                        position,
                        TaintCodeFlows.of(model, df, node, TaintKind.EXTERNAL_INPUT, flagged,
                                position, "動的SQLの文字列へ組み込む"),
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

package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.dataflow.DataFlowFacts;
import jp.cobolinsight.core.dataflow.ProgramDataFlow;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jp.cobolinsight.rules.dataflow.DataFlowSupport.Section;

/**
 * R001 Reference to an uninitialized variable. Detects, via reaching-definitions analysis (whether
 * a synthetic uninitialized definition at the entry reaches the use node), places where an
 * elementary item in WORKING-STORAGE / LOCAL-STORAGE / LINKAGE that has no VALUE clause is
 * referenced on some execution path before its value is set. The query is restricted to variables
 * read at a use node; a FILE-section item, a group item, a PROCEDURE DIVISION USING parameter
 * (initialized by the caller), and a special register are excluded.
 *
 * <p>The query is further restricted to items explicitly assigned by some statement in the program.
 * An item with no explicit assignment that is implicitly set by I/O, the runtime, or the loop
 * mechanism — such as a file status, a CICS response code, an SQL/CICS host variable, or a PERFORM
 * VARYING control variable — appears uninitialized under reaching-definitions analysis and is
 * therefore excluded (the defect is limited to a configuration with "a path where it is explicitly
 * initialized and a path where it is not").
 */
public final class UninitializedVariableRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("R001", "未初期化変数の参照", "データフロー")
            .summary("値を設定される前に参照され得るデータ項目を検出します。")
            .rationale("記憶域に残った値をそのまま使うため、"
                    + "実行のたびに結果が変わり、再現しない不具合になります。")
            .detection("到達定義解析で、入口に置いた未初期化の定義が使用位置へ届くものを"
                    + "検出します。対象は、プログラム内のいずれかの文が明示的に代入する基本項目に"
                    + "限ります。FILE 節の項目・集団項目・PROCEDURE DIVISION USING の引数・"
                    + "特殊レジスタと、ファイル状態や CICS 応答コードのように実行系が暗黙に"
                    + "設定する項目は対象外とします。")
            .remedy("宣言へ VALUE 句を置くか、参照前に INITIALIZE・MOVE で値を設定します。")
            .example("""
                    01  WS-COUNT  PIC 9(4).
                        IF WS-FLG = "Y"
                            MOVE 1 TO WS-COUNT
                        END-IF.
                        DISPLAY WS-COUNT.
                    """, """
                    01  WS-COUNT  PIC 9(4) VALUE ZERO.
                        IF WS-FLG = "Y"
                            MOVE 1 TO WS-COUNT
                        END-IF.
                        DISPLAY WS-COUNT.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT, Command.REPORT)
            .targets(AssetKind.COBOL)
            .needs(Needs.SEMANTIC, Needs.CFG, Needs.DATAFLOW, Needs.SOURCE_TEXT)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        DataFlowFacts facts = context.artifact(DataFlowFacts.class).orElse(null);
        ControlFlowGraphs cfgs = context.artifact(ControlFlowGraphs.class).orElse(null);
        SourceTextIndex texts = context.artifact(SourceTextIndex.class).orElse(null);
        if (facts == null || cfgs == null || texts == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            ProgramDataFlow df = facts.of(model).orElse(null);
            ControlFlowGraph cfg = cfgs.of(model).orElse(null);
            if (df != null && cfg != null) {
                evaluate(model, cfg, df, new DataFlowSupport(model, texts), findings);
            }
        }
        return findings;
    }

    private void evaluate(CobolSemanticModel model, ControlFlowGraph cfg, ProgramDataFlow df,
            DataFlowSupport support, List<Finding> findings) {
        Set<String> explicitlyDefined = new LinkedHashSet<>();
        for (CfgNode node : cfg.nodes()) {
            explicitlyDefined.addAll(df.defsAt(node));
        }
        for (CfgNode node : cfg.nodes()) {
            Statement statement = node.statement().orElse(null);
            if (statement == null) {
                continue;
            }
            for (String var : checkedUses(node, statement, df)) {
                if (!explicitlyDefined.contains(var) || !eligible(support, var)
                        || !df.mayReachUninitialized(node, var)) {
                    continue;
                }
                int line = statement.range().start().line();
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "未初期化の可能性がある " + var + " を、値を設定する前に参照している。"
                                + "先行経路によっては不定値を用いる。",
                        new SourcePosition(model.sourceFile(), line, 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    private static final Set<String> ARITHMETIC_VERBS =
            Set.of("COMPUTE", "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE");

    /**
     * Variables appearing in a use context that is checked for being uninitialized. Restricted to a
     * condition expression (IF/EVALUATE/UNTIL), an arithmetic expression's input operand, and a
     * DISPLAY output target. A CALL actual argument, an SQL/CICS host variable, a MOVE source, and
     * the like are not targeted, because input and output (an INTO or BY REFERENCE receiver) cannot
     * be distinguished by the def/use approximation and would cause false positives.
     */
    private static Set<String> checkedUses(CfgNode node, Statement statement, ProgramDataFlow df) {
        if (statement instanceof CompoundStatement) {
            return df.usesAt(node);
        }
        if (statement instanceof SimpleStatement simple) {
            String verb = simple.verb().toUpperCase(java.util.Locale.ROOT);
            if (ARITHMETIC_VERBS.contains(verb)) {
                Set<String> uses = new LinkedHashSet<>(df.usesAt(node));
                uses.removeAll(df.defsAt(node));
                return uses;
            }
            if ("DISPLAY".equals(verb)) {
                return df.usesAt(node);
            }
        }
        return Set.of();
    }

    private static boolean eligible(DataFlowSupport support, String var) {
        if (!support.isDeclared(var) || support.isGroupItem(var) || !support.isValueless(var)
                || support.isExternallyInitialized(var) || support.isSpecialRegister(var)) {
            return false;
        }
        Section section = support.sectionOf(var);
        return section == Section.WORKING_STORAGE || section == Section.LOCAL_STORAGE
                || section == Section.LINKAGE;
    }
}

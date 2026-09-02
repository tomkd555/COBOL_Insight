package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.dataflow.DataFlowFacts;
import jp.cobolinsight.core.dataflow.ProgramDataFlow;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.CodeFlow;
import jp.cobolinsight.core.finding.CodeFlowStep;
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
import jp.cobolinsight.rules.cfg.CfgSupport;
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

    private static final RuleMeta META = RuleMeta.named("R001", "未初期化のデータ項目の参照", "データフロー")
            .summary("値を設定する前に参照し得るデータ項目を検出する。")
            .rationale("記憶域に残った値をそのまま使うため、"
                    + "実行のたびに結果が変わり、再現しない不具合になる。")
            .detection("到達定義解析で、入口に置いた未初期化の定義が使用位置に到達するものを"
                    + "検出する。対象は、プログラム内のいずれかの文が明示的に値を設定する基本項目に"
                    + "限る。ファイル節の項目・集団項目・PROCEDURE DIVISION USING の引数・"
                    + "特殊レジスタと、入出力状態や CICS の応答コードのように実行系が暗黙に"
                    + "設定する項目は対象外とする。")
            .remedy("宣言に VALUE 句を置くか、参照の前に INITIALIZE・MOVE で値を設定する。")
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
                findings.add(finding(model, cfg, df, support, var, line));
            }
        }
    }

    /**
     * The finding text names the declaration, the use, and the statements that do set the item, so
     * the reader sees which of those a path can skip.
     */
    private static Finding finding(CobolSemanticModel model, ControlFlowGraph cfg,
            ProgramDataFlow df, DataFlowSupport support, String var, int line) {
        String file = model.sourceFile();
        List<CodeFlowStep> steps = new ArrayList<>();
        StringBuilder message = new StringBuilder(var);
        support.item(var).ifPresent(item -> {
            int decl = item.position().line();
            message.append("（宣言 ").append(decl).append("行）");
            steps.add(CfgSupport.step(item.position().file(), decl,
                    "宣言。VALUE 句がなく、初期値は不定"));
        });
        message.append("を").append(line)
                .append("行で参照しているが、ここへ至る経路のどれかで値が未設定のまま。");
        List<Integer> setLines = new ArrayList<>();
        for (CfgNode node : cfg.nodes()) {
            if (df.defsAt(node).contains(var)) {
                node.statement().ifPresent(statement -> {
                    int at = statement.range().start().line();
                    if (!setLines.contains(at)) {
                        setLines.add(at);
                    }
                });
            }
        }
        setLines.sort(null);
        if (!setLines.isEmpty()) {
            message.append("値を設定するのは ").append(setLines.size() == 1
                    ? setLines.get(0) + "行だけで、"
                    : setLines.size() + "箇所（" + joinLines(setLines) + "）だけで、");
            message.append("そこを通らないまま ").append(line).append("行に至る経路がある。");
            for (int at : setLines.subList(0, Math.min(setLines.size(), 3))) {
                steps.add(CfgSupport.step(file, at,
                        var + " に値を設定する文。この文を通らない経路がある"));
            }
        }
        message.append("宣言に VALUE 句を置くか、").append(line)
                .append("行より前で必ず MOVE・INITIALIZE を通す。");
        steps.add(CfgSupport.step(file, line, "未設定のまま参照する箇所"));
        return new Finding(META.id(), META.defaultSeverity().toLevel(), message.toString(),
                new SourcePosition(file, line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET),
                List.of(new CodeFlow(steps)), List.of());
    }

    private static String joinLines(List<Integer> lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.append("・");
            }
            out.append(lines.get(i)).append("行");
        }
        return out.toString();
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

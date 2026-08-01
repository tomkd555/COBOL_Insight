package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraphs;
import jp.cobolinsight.engineapi.dataflow.DataFlowFacts;
import jp.cobolinsight.engineapi.dataflow.ProgramDataFlow;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.CompoundStatement;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jp.cobolinsight.rules.dataflow.DataFlowSupport.Section;

/**
 * R001 未初期化変数の参照。VALUE 句を持たない WORKING-STORAGE / LOCAL-STORAGE / LINKAGE の基本項目が、
 * ある実行経路で値を設定される前に参照される箇所を、到達定義解析(入口に合成した未初期化定義が
 * 使用ノードへ届くか)で検出する。照会は使用ノードで読み取る変数に限り、FILE 節の項目・集団項目・
 * PROCEDURE DIVISION USING 引数(呼出元が初期化する)・特殊レジスタは対象外とする。
 *
 * <p>照会は、プログラム内のいずれかの文が明示的に代入する項目に限る。ファイルステータス・CICS 応答
 * コード・SQL/CICS のホスト変数・PERFORM VARYING 制御変数のように、明示代入を持たず入出力・実行系や
 * ループ機構が暗黙に設定する項目は、到達定義解析では未初期化に見えるため対象から外す(欠陥は「明示的に
 * 初期化される経路とされない経路がある」構成に限られる)。
 */
public final class UninitializedVariableRule implements Rule {

    @Override
    public String id() {
        return "R001";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("未初期化変数の参照", "データフロー")
                .summary("値を設定される前に参照され得るデータ項目を検出する。")
                .rationale("記憶域に残った値をそのまま使うため、"
                        + "実行のたびに結果が変わり、再現しない不具合になる。")
                .detection("到達定義解析で、入口に置いた未初期化の定義が使用位置へ届くものを"
                        + "検出する。対象は、プログラム内のいずれかの文が明示的に代入する基本項目に"
                        + "限る。FILE 節の項目・集団項目・PROCEDURE DIVISION USING の引数・"
                        + "特殊レジスタと、ファイル状態や CICS 応答コードのように実行系が暗黙に"
                        + "設定する項目は対象外とする。")
                .remedy("宣言へ VALUE 句を置くか、参照前に INITIALIZE・MOVE で値を設定する。")
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
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
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
     * 未初期化を問う使用文脈に現れる変数。条件式(IF/EVALUATE/UNTIL)・算術式の入力オペランド・
     * DISPLAY の出力対象に限る。CALL 実引数・SQL/CICS ホスト変数・MOVE 送信元などは、入力と出力
     * (INTO・BY REFERENCE 受け)を def/use 近似で区別できず誤検出になるため対象にしない。
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

package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.semantic.Procedure;
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
import java.util.Set;

/**
 * R022 CICS RETURN欠如による疑似会話の途絶。CICS 参加プログラムが EXEC CICS RETURN TRANSID を
 * 1つも持たずに終端に達する場合、疑似会話トランザクションの制御が CICS へ戻らないため検出する。
 * CICS 参加は、自プログラムに CICS ブロックを持つか、他プログラムの XCTL/LINK/START の遷移先で
 * あるかで判定する(呼出関係グラフもトランザクション定義表も用いない)。
 */
public final class CicsReturnMissingRule implements Rule {

    private static final RuleMeta META =
            RuleMeta.named("R022", "CICS RETURN文欠如による疑似会話の途絶", "制御フロー")
                    .summary("EXEC CICS RETURN TRANSID を1つも持たないまま終端に達する"
                            + "CICS 参加プログラムを検出します。")
                    .rationale("制御が CICS へ戻らず、次の入力を受け付ける状態が作られないため、"
                            + "疑似会話が途切れて端末が応答しなくなります。")
                    .detection("自プログラムに CICS ブロックを持つか、他プログラムの XCTL・LINK・START の"
                            + "遷移先であるプログラムを CICS 参加とみなし、"
                            + "RETURN TRANSID の有無で判定します。")
                    .remedy("処理の終わりに EXEC CICS RETURN TRANSID を置き、"
                            + "次に起動するトランザクションを指定します。")
                    .example("""
                            EXEC CICS SEND MAP('MAP01') MAPSET('MAPSET1') END-EXEC.
                            GOBACK.
                            """, """
                            EXEC CICS SEND MAP('MAP01') MAPSET('MAPSET1') END-EXEC.
                            EXEC CICS RETURN TRANSID('TR01') COMMAREA(WS-COMM) END-EXEC.
                            """)
                    .severity(Severity.MEDIUM)
                    .commands(Command.LINT, Command.REPORT)
                    .targets(AssetKind.COBOL)
                    .needs(Needs.SEMANTIC)
                    .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        Set<String> transferTargets = transferTargets(context);
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            boolean participates = hasCicsBlock(model)
                    || transferTargets.contains(CfgSupport.upper(model.programId()));
            if (!participates || hasReturnTransid(model)) {
                continue;
            }
            Statement terminal = firstTerminal(model);
            if (terminal != null) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "CICS 参加プログラム " + model.programId()
                                + " は EXEC CICS RETURN を持たずに終端する。"
                                + "疑似会話の制御が CICS へ戻らない。",
                        new SourcePosition(model.sourceFile(), terminal.range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
        return findings;
    }

    private static Set<String> transferTargets(AnalysisContext context) {
        Set<String> targets = new LinkedHashSet<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            for (EmbeddedBlock block : model.embeddedBlocks()) {
                if (block.kind() == EmbeddedBlockKind.CICS_XCTL
                        || block.kind() == EmbeddedBlockKind.CICS_LINK
                        || block.kind() == EmbeddedBlockKind.CICS_START) {
                    String program = block.operands().get("PROGRAM");
                    if (program != null && !program.isBlank()) {
                        targets.add(CfgSupport.upper(program));
                    }
                }
            }
        }
        return targets;
    }

    private static boolean hasCicsBlock(CobolSemanticModel model) {
        return model.embeddedBlocks().stream().anyMatch(block -> block.kind().isCics());
    }

    private static boolean hasReturnTransid(CobolSemanticModel model) {
        return model.embeddedBlocks().stream()
                .anyMatch(block -> block.kind() == EmbeddedBlockKind.CICS_RETURN_TRANSID);
    }

    private static Statement firstTerminal(CobolSemanticModel model) {
        // 段落 top-level の終端(無条件の可能性が高い)を優先する。
        for (Procedure procedure : model.procedures()) {
            for (Statement statement : procedure.statements()) {
                if (isTerminal(statement)) {
                    return statement;
                }
            }
        }
        // top-level に無ければ、IF/EVALUATE 内に入れ子の終端を探す。
        Statement[] nested = {null};
        for (Procedure procedure : model.procedures()) {
            CfgSupport.walk(procedure.statements(), statement -> {
                if (nested[0] == null && isTerminal(statement)) {
                    nested[0] = statement;
                }
            });
            if (nested[0] != null) {
                break;
            }
        }
        return nested[0];
    }

    private static boolean isTerminal(Statement statement) {
        if (statement instanceof SimpleStatement simple) {
            String verb = CfgSupport.upper(simple.verb());
            return verb.equals("STOP") || verb.equals("GOBACK")
                    || (verb.equals("EXIT") && CfgSupport.upper(simple.text()).contains("PROGRAM"));
        }
        return false;
    }
}

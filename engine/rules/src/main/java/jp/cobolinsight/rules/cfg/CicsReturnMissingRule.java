package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlock;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlockKind;
import jp.cobolinsight.engineapi.semantic.Procedure;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;

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

    @Override
    public String id() {
        return "R022";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.MEDIUM;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.CONTROL_FLOW;
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
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
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

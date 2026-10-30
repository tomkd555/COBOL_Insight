package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.semantic.PerformRelation;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.ProcedureMap;
import jp.cobolinsight.core.semantic.ProcedureNames;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * R043 A HANDLE ABEND exit that neither backs the unit of work out nor abends. Control reaches the
 * label with the updates of the failed task still uncommitted; an exit that only sends a message
 * and returns lets CICS commit them at the syncpoint the RETURN takes. The label paragraph and the
 * paragraphs it performs are searched for EXEC CICS SYNCPOINT ROLLBACK and EXEC CICS ABEND.
 */
public final class HandleAbendRollbackMissingRule implements Rule {

    private static final RuleMeta META =
            RuleMeta.named("R043", "SYNCPOINT ROLLBACK のない HANDLE ABEND の出口", "CICS")
                    .summary("EXEC CICS HANDLE ABEND の LABEL が示す段落のうち、"
                            + "取り消しも異常終了もしないものを検出します。")
                    .rationale("異常終了までの更新が取り消されないまま同期点に達し、"
                            + "中途半端な状態の更新が確定します。")
                    .detection("EXEC CICS HANDLE ABEND の LABEL が示す段落と、"
                            + "そこから PERFORM でたどれる段落に、"
                            + "EXEC CICS SYNCPOINT ROLLBACK も EXEC CICS ABEND も"
                            + "ないものを検出します。"
                            + "PROGRAM・CANCEL・RESET を指定した HANDLE ABEND は対象外です。")
                    .remedy("出口の段落で EXEC CICS SYNCPOINT ROLLBACK を実行してから"
                            + "終了処理に移ってください。")
                    .example("""
                            EXEC CICS HANDLE ABEND LABEL(9500-ABEND) END-EXEC
                            ...
                            9500-ABEND.
                                EXEC CICS RETURN END-EXEC.
                            """, """
                            EXEC CICS HANDLE ABEND LABEL(9500-ABEND) END-EXEC
                            ...
                            9500-ABEND.
                                EXEC CICS SYNCPOINT ROLLBACK END-EXEC
                                EXEC CICS RETURN END-EXEC.
                            """)
                    .severity(Severity.MEDIUM)
                    .commands(Command.LINT)
                    .targets(AssetKind.COBOL)
                    .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            ProcedureNames names = new ProcedureNames(model.procedures());
            ProcedureMap procedureMap = new ProcedureMap(model);
            // The section of the procedure whose statement holds each EXEC CICS block, which a
            // label is resolved from; a block outside every procedure resolves from no section.
            Map<EmbeddedBlock, Optional<String>> sectionByBlock = new IdentityHashMap<>();
            for (Procedure procedure : model.procedures()) {
                CfgSupport.walk(procedure.statements(), statement -> {
                    for (EmbeddedBlock block : procedureMap.blocksOf(statement)) {
                        sectionByBlock.put(block, ProcedureNames.sectionOf(procedure));
                    }
                });
            }
            for (EmbeddedBlock block : model.embeddedBlocks()) {
                String label = handleAbendLabel(block);
                if (label == null) {
                    continue;
                }
                int start = names.indexOf(label, Optional.empty(),
                        sectionByBlock.getOrDefault(block, Optional.empty()));
                if (start >= 0 && recovers(model, names, procedureMap, start)) {
                    continue;
                }
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        CfgSupport.upper(label) + " は ROLLBACK を実行しません。"
                                + "異常終了までの更新が取り消されずに確定します。",
                        new SourcePosition(block.range().start().file(),
                                CfgSupport.operandLine(block, "LABEL"), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
        return findings;
    }

    /** The LABEL operand of an {@code EXEC CICS HANDLE ABEND}, or null for any other command. */
    private static String handleAbendLabel(EmbeddedBlock block) {
        if (block.kind() != EmbeddedBlockKind.CICS_OTHER
                || !CfgSupport.cicsBody(block.text()).startsWith("HANDLE ABEND")) {
            return null;
        }
        String label = block.operands().get("LABEL");
        return label == null || label.isBlank() ? null : label.trim();
    }

    /**
     * Whether the label paragraph, or a paragraph it performs, backs the unit of work out or
     * abends. A PERFORM brings in every procedure of its range, THRU end and a section's
     * paragraphs included.
     */
    private static boolean recovers(CobolSemanticModel model, ProcedureNames names,
            ProcedureMap procedureMap, int start) {
        List<Procedure> procedures = model.procedures();
        Set<Integer> visited = new LinkedHashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            int index = queue.removeFirst();
            if (!visited.add(index)) {
                continue;
            }
            Procedure procedure = procedures.get(index);
            if (backsOut(procedure)) {
                return true;
            }
            Optional<String> section = ProcedureNames.sectionOf(procedure);
            for (PerformRelation perform : procedureMap.performsIn(index)) {
                names.rangeOf(perform, section).ifPresent(range -> {
                    for (int i = range.from(); i <= range.to(); i++) {
                        queue.add(i);
                    }
                });
            }
        }
        return false;
    }

    private static boolean backsOut(Procedure procedure) {
        boolean[] found = {false};
        CfgSupport.walk(procedure.statements(), statement -> {
            if (!(statement instanceof SimpleStatement simple)
                    || !"EXEC CICS".equals(simple.verb())) {
                return;
            }
            String body = CfgSupport.cicsBody(simple.text());
            if (body.startsWith("ABEND")
                    || (body.startsWith("SYNCPOINT") && body.contains("ROLLBACK"))) {
                found[0] = true;
            }
        });
        return found[0];
    }
}

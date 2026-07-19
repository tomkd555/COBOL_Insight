package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.GoToStatement;
import jp.cobolinsight.engineapi.semantic.Procedure;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * R009 構造化から逸脱する GO TO。節(SECTION)をまたぐ GO TO を検出する。GO TO を含む段落の
 * 所属節と、飛び先段落の所属節が異なる場合に検出する。同一節内の GO TO と、PERFORM THRU 範囲
 * への割り込み(R007 が扱う)は対象外とする。
 */
public final class GoToStructureDeviationRule implements Rule {

    @Override
    public String id() {
        return "R009";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ADVISORY;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.CONTROL_FLOW;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            List<GoToStatement> gotos = new ArrayList<>();
            for (Procedure procedure : model.procedures()) {
                CfgSupport.walk(procedure.statements(), statement -> {
                    if (statement instanceof GoToStatement goTo) {
                        gotos.add(goTo);
                    }
                });
            }
            for (GoToStatement goTo : gotos) {
                Procedure holder = CfgSupport.containingProcedure(model, goTo);
                if (holder == null || holder.sectionName().isEmpty()) {
                    continue;
                }
                String fromSection = CfgSupport.upper(holder.sectionName().get());
                for (String rawTarget : goTo.targets()) {
                    Optional<String> targetSection = sectionOf(model, rawTarget);
                    if (targetSection.isEmpty()) {
                        continue;
                    }
                    if (!CfgSupport.upper(targetSection.get()).equals(fromSection)) {
                        findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                                "GO TO " + rawTarget + " は所属節 " + holder.sectionName().get()
                                        + " から別の節 " + targetSection.get()
                                        + " へ分岐しており、節の構造から逸脱している。",
                                new SourcePosition(model.sourceFile(),
                                        goTo.range().start().line(), 1,
                                        SourcePosition.UNKNOWN_BYTE_OFFSET)));
                        break;
                    }
                }
            }
        }
        return findings;
    }

    private static Optional<String> sectionOf(CobolSemanticModel model, String paragraphName) {
        String wanted = CfgSupport.upper(paragraphName);
        for (Procedure procedure : model.procedures()) {
            if (CfgSupport.upper(procedure.name()).equals(wanted)) {
                return procedure.sectionName();
            }
        }
        return Optional.empty();
    }
}

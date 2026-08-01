package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.PerformRelation;
import jp.cobolinsight.engineapi.semantic.Procedure;
import jp.cobolinsight.engineapi.semantic.ProcedureKind;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R008 PERFORM単独段落名の直接指定。PERFORM文が単一の段落名のみを指定し、THRU句で終端を
 * 明示していない箇所を検出する。遷移先がセクションであるPERFORM文とインラインPERFORMは
 * 対象外とする。段落とセクションの両方に存在する名前は遷移先種別が参照位置に依存して
 * あいまいになるため、判定を保留する(誤検出よりも未検出を選ぶ)。
 */
public final class PerformSingleParagraphRule implements Rule {

    @Override
    public String id() {
        return "R008";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("PERFORM単独段落名の直接指定", "制御フロー")
                .summary("THRU 句で終端を明示せず、単一の段落名だけを指定した PERFORM 文を検出する。")
                .rationale("処理の追加で段落を分けたとき、範囲の終端が書かれていないため、"
                        + "呼出側の直し漏れで新しい段落が実行されない。")
                .detection("遷移先が段落の PERFORM 文のうち THRU 句を持たないものを検出する。"
                        + "セクションを対象とする PERFORM とインライン PERFORM は対象外とし、"
                        + "段落とセクションの双方に同じ名前がある場合は判定を保留する。")
                .remedy("PERFORM ... THRU ...-EXIT の形にし、範囲の終端を EXIT 段落で明示する。")
                .example("""
                        PERFORM CALC-TAX.
                        """, """
                        PERFORM CALC-TAX THRU CALC-TAX-EXIT.
                        """)
                .build();
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.MEDIUM;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.SYNTAX;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            Map<String, ProcedureKind> kindByName = new HashMap<>();
            Set<String> ambiguousNames = new HashSet<>();
            for (Procedure procedure : model.procedures()) {
                String name = CobolTexts.upper(procedure.name());
                ProcedureKind previous = kindByName.putIfAbsent(name, procedure.kind());
                if (previous != null && previous != procedure.kind()) {
                    ambiguousNames.add(name);
                }
            }
            for (PerformRelation perform : model.performs()) {
                if (perform.thruProcedure().isPresent()) {
                    continue;
                }
                String target = CobolTexts.upper(perform.targetProcedure());
                if (ambiguousNames.contains(target)
                        || kindByName.get(target) != ProcedureKind.PARAGRAPH) {
                    continue;
                }
                findings.add(Finding.of("R008", Severity.MEDIUM.toLevel(),
                        "PERFORM文が単一の段落名 " + perform.targetProcedure()
                                + " のみを指定し、THRU句で終端を明示していない。",
                        perform.range().start()));
            }
        }
        return findings;
    }
}

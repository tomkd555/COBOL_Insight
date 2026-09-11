package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.PerformRelation;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.ProcedureKind;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R008 PERFORM naming a single paragraph directly. Detects places where a PERFORM
 * statement names only a single paragraph and does not make its end explicit with a THRU
 * clause. A PERFORM statement targeting a section, and an inline PERFORM, are excluded.
 * A name that exists as both a paragraph and a section is ambiguous, since the target
 * kind depends on the reference site, so judgment is withheld for it (preferring a
 * missed detection over a false positive).
 */
public final class PerformSingleParagraphRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("R008", "THRU 句のない PERFORM 文", "制御フロー")
            .summary("THRU 句で終端を明示せず、単一の段落名だけを指定した PERFORM 文を検出します。")
            .rationale("処理の追加で段落を分けたとき、範囲の終端が書かれていないため、"
                    + "呼び出し側の直し漏れで新しい段落が実行されません。")
            .detection("段落を対象とする PERFORM 文のうち THRU 句を持たないものを検出します。"
                    + "節を対象とする PERFORM 文とインライン PERFORM 文は対象外で、"
                    + "段落と節の双方に同じ名前がある場合は検出を保留します。")
            .remedy("PERFORM ... THRU ...-EXIT の形にし、範囲の終端を EXIT 段落で"
                    + "明示してください。")
            .example("""
                    PERFORM CALC-TAX.
                    """, """
                    PERFORM CALC-TAX THRU CALC-TAX-EXIT.
                    """)
            .severity(Severity.MEDIUM)
            // Off by default. It produced 43 hits in samples and 51 in the corpus used to
            // measure false positives, and none of them was actually a defect. Whether to
            // add THRU is a site's own coding convention, not a bug, so a site that follows
            // that convention is expected to turn this on via rules.json.
            .defaultEnabled(false)
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
                        perform.targetProcedure() + " を PERFORM する文に THRU 句がありません。",
                        perform.range().start()));
            }
        }
        return findings;
    }
}

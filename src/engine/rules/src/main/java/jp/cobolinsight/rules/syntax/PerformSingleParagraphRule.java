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
 * R008 PERFORM単独段落名の直接指定。PERFORM文が単一の段落名のみを指定し、THRU句で終端を
 * 明示していない箇所を検出する。遷移先がセクションであるPERFORM文とインラインPERFORMは
 * 対象外とする。段落とセクションの両方に存在する名前は遷移先種別が参照位置に依存して
 * あいまいになるため、判定を保留する(誤検出よりも未検出を選ぶ)。
 */
public final class PerformSingleParagraphRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("R008", "PERFORM単独段落名の直接指定", "制御フロー")
            .summary("THRU 句で終端を明示せず、単一の段落名だけを指定した PERFORM 文を検出します。")
            .rationale("処理の追加で段落を分けたとき、範囲の終端が書かれていないため、"
                    + "呼出側の直し漏れで新しい段落が実行されません。")
            .detection("遷移先が段落の PERFORM 文のうち THRU 句を持たないものを検出します。"
                    + "セクションを対象とする PERFORM とインライン PERFORM は対象外とし、"
                    + "段落とセクションの双方に同じ名前がある場合は判定を保留します。")
            .remedy("PERFORM ... THRU ...-EXIT の形にし、範囲の終端を EXIT 段落で明示します。")
            .example("""
                    PERFORM CALC-TAX.
                    """, """
                    PERFORM CALC-TAX THRU CALC-TAX-EXIT.
                    """)
            .severity(Severity.MEDIUM)
            // 既定では動かさない。samples で 43 件、誤検出計測用の corpus で 51 件を出しながら、
            // そのどれも欠陥ではなかった。THRU を付けるかどうかは現場の書き方の取り決めであって
            // 不具合ではないため、その取り決めを持つ現場が rules.json で入れる形にする。
            .defaultEnabled(false)
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

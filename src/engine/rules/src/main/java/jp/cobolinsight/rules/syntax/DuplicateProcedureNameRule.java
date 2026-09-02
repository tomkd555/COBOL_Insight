package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R023 Duplicate paragraph or section names. Detects places where a paragraph name or a
 * section name is declared more than once within the same program. Judged on the pair of
 * owning section and name, so a same-named paragraph in a different section (which is
 * legal) is not detected. Reported at the location of the second and later declarations.
 */
public final class DuplicateProcedureNameRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("R023", "段落名・節名の重複", "制御フロー")
            .summary("同一プログラム内で同じ名前の段落または節を"
                    + "重ねて宣言している箇所を検出します。")
            .rationale("PERFORM 文や GO TO 文の移行先が一意に定まらず、"
                    + "意図した側とは別の宣言に制御が移り得ます。")
            .detection("所属する節と名前の組が同じ宣言が 2 件以上あるものを検出し、"
                    + "2 件目以降の宣言位置で報告します。"
                    + "異なる節にある同名の段落は合法のため対象外です。")
            .remedy("いずれかの名前を改め、参照している側も併せて直してください。")
            .example("""
                    CALC-TAX.
                        COMPUTE WS-TAX = WS-AMT * 0.10.
                    CALC-TAX.
                        COMPUTE WS-TAX = WS-AMT * 0.08.
                    """, """
                    CALC-TAX-STD.
                        COMPUTE WS-TAX = WS-AMT * 0.10.
                    CALC-TAX-REDUCED.
                        COMPUTE WS-TAX = WS-AMT * 0.08.
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
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            Map<String, List<Procedure>> byName = new LinkedHashMap<>();
            // The duplicate-detection key is the pair of owning section name and procedure
            // name. The separator is NUL (U+0000), a character that never appears in a
            // COBOL name, to avoid colliding with characters in the name. Embedding a raw
            // NUL would make this source file itself get treated as binary, so it must
            // always be written as the escape \0.
            for (Procedure procedure : model.procedures()) {
                String key = procedure.sectionName().map(CobolTexts::upper).orElse("")
                        + "\0" + CobolTexts.upper(procedure.name());
                byName.computeIfAbsent(key, name -> new ArrayList<>()).add(procedure);
            }
            for (List<Procedure> declarations : byName.values()) {
                if (declarations.size() < 2) {
                    continue;
                }
                List<Procedure> ordered = declarations.stream()
                        .sorted(Comparator.comparingInt(p -> p.range().start().line()))
                        .toList();
                int firstLine = ordered.get(0).range().start().line();
                for (Procedure duplicate : ordered.subList(1, ordered.size())) {
                    findings.add(Finding.of("R023", Severity.MEDIUM.toLevel(),
                            duplicate.name() + " が重複して宣言されています。" + firstLine
                                    + "行の宣言と区別できず、PERFORM 文の移行先があいまいになります。",
                            duplicate.range().start()));
                }
            }
        }
        return findings;
    }
}

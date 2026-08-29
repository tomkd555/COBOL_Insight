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
 * R023 パラグラフ・セクション名の重複。同一プログラム内でパラグラフ名またはセクション名が
 * 重複して宣言されている箇所を検出する。所属セクションと名前の組で判定するため、異なる
 * セクションの同名パラグラフ(合法)は検出しない。2件目以降の宣言位置で報告する。
 */
public final class DuplicateProcedureNameRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("R023", "パラグラフ・セクション名の重複", "制御フロー")
            .summary("同一プログラム内で同じ名前のパラグラフまたはセクションが"
                    + "重ねて宣言されている箇所を検出します。")
            .rationale("PERFORM や GO TO の遷移先が一意に定まらず、"
                    + "意図した側とは別の宣言へ制御が移ることがあります。")
            .detection("所属セクションと名前の組で判定し、2件目以降の宣言位置で報告します。"
                    + "異なるセクションにある同名パラグラフは合法のため検出しません。")
            .remedy("いずれかの名前を改め、参照している側も併せて直します。")
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
            // 重複判定のキーは所属セクション名と手続き名の組。区切りにはCOBOLの名前に現れない
            // NUL(U+0000)を用い、名前に含まれる文字との衝突を避ける。生のNULを埋めるとこの
            // ソース自体がバイナリ扱いになるため、必ずエスケープ \0 で書く。
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
                            "パラグラフ・セクション名 " + duplicate.name()
                                    + " が重複して宣言されており、PERFORM文の遷移先が構文上"
                                    + "あいまいになる(最初の宣言は" + firstLine + "行目)。",
                            duplicate.range().start()));
                }
            }
        }
        return findings;
    }
}

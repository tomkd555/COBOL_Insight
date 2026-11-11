package jp.cobolinsight.rules.easytrieve;

import jp.cobolinsight.core.easytrieve.EasytrieveActivity;
import jp.cobolinsight.core.easytrieve.EasytrieveProcedure;
import jp.cobolinsight.core.easytrieve.EasytrieveProgram;
import jp.cobolinsight.core.easytrieve.EasytrieveStatement;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * R105 A PROC nothing performs. It is compiled, listed and maintained, and it never runs: either
 * the PERFORM that called it was removed and the PROC forgotten, or the PERFORM was never written
 * and the processing the PROC holds is missing from the run.
 */
public final class UnusedProcedureRule implements Rule {

    /** The report procedures the report writer itself invokes, by their fixed names. */
    private static final Set<String> REPORT_PROCEDURES = Set.of("REPORT-INPUT", "BEFORE-LINE",
            "AFTER-LINE", "BEFORE-BREAK", "AFTER-BREAK", "ENDPAGE", "TERMINATION");

    private static final RuleMeta META = RuleMeta
            .named("R105", "どこからも実行されない PROC", RuleCategory.MAINTAINABILITY)
            .technology(Technology.EASYTRIEVE)
            .summary("PERFORM にも START・FINISH・BEFORE にも現れない PROC を検出します。")
            .rationale("実行されないコードが保守の対象として残ります。PERFORM の書き忘れなら、"
                    + "その PROC の処理が実行されていません。")
            .detection("アクティビティの PROC のうち、同じアクティビティのどの PERFORM 文にも、"
                    + "JOB 文の START・FINISH、SORT 文の BEFORE にも名前が現れないものを"
                    + "検出します。REPORT の下の、名前が決まっている報告書 PROC は対象外です。")
            .remedy("不要なら PROC を削除し、必要なら PERFORM を書いてください。")
            .severity(Severity.LOW)
            .commands(Command.LINT)
            .targets(AssetKind.EASYTRIEVE, AssetKind.JCL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (EasytrieveProgram program : EasytrieveSupport.programs(context)) {
            for (EasytrieveActivity activity : program.activities()) {
                Set<String> performed = new HashSet<>();
                List<String> header = activity.header().words();
                for (String keyword : List.of("START", "FINISH", "BEFORE")) {
                    int at = header.indexOf(keyword);
                    if (at >= 0 && at + 1 < header.size()) {
                        performed.add(header.get(at + 1));
                    }
                }
                for (EasytrieveStatement statement : activity.executableStatements()) {
                    if (statement.is("PERFORM")) {
                        performed.add(statement.word(1));
                    }
                }
                for (EasytrieveProcedure procedure : activity.procedures()) {
                    if (!performed.contains(procedure.name())
                            && !REPORT_PROCEDURES.contains(procedure.name())) {
                        findings.add(EasytrieveSupport.finding(META, procedure.position(),
                                "PROC " + procedure.name() + " は、このアクティビティの"
                                        + "どこからも実行されません。"));
                    }
                }
            }
        }
        return findings;
    }
}

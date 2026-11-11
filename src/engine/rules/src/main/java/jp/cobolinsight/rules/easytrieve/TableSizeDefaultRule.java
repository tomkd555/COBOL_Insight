package jp.cobolinsight.rules.easytrieve;

import jp.cobolinsight.core.easytrieve.EasytrieveFile;
import jp.cobolinsight.core.easytrieve.EasytrieveProgram;
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
 * R104 An external table with no size on its FILE statement. A table file is loaded whole before
 * the first SEARCH, into space for as many rows as the statement says — and for 256 rows when it
 * says nothing. The table grows with the business; on the day it reaches row 257 the job that has
 * run for years ends at its first SEARCH.
 */
public final class TableSizeDefaultRule implements Rule {

    private static final int DEFAULT_ENTRIES = 256;

    private static final RuleMeta META = RuleMeta
            .named("R104", "最大件数を指定しない外部表ファイル", RuleCategory.ABEND)
            .technology(Technology.EASYTRIEVE)
            .symptom("表のあふれによる異常終了（既定 256 件）")
            .summary("TABLE を指定した外部ファイルのうち、最大件数を書いていないものを検出します。")
            .rationale("最大件数を省略すると 256 件分の領域しか確保されません。表の件数が"
                    + "それを超えた日に、最初の SEARCH で異常終了します。")
            .detection("FILE 文に TABLE があり、その後ろに件数の数字も INSTREAM もないものを"
                    + "検出します。件数がプログラムから読み取れる、ストリーム内の表は対象外です。")
            .remedy("TABLE の後ろに、将来の増加を見込んだ最大件数を書いてください。")
            .example("""
                    FILE TENTBL TABLE
                      ARG   1  4 A
                      DESC  5 20 A
                    """, """
                    FILE TENTBL TABLE 5000
                      ARG   1  4 A
                      DESC  5 20 A
                    """)
            .severity(Severity.MEDIUM)
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
        Set<String> reportedAt = new HashSet<>();
        for (EasytrieveProgram program : EasytrieveSupport.programs(context)) {
            for (EasytrieveFile file : program.files()) {
                if (file.table() && !file.instreamTable() && file.tableEntries().isEmpty()
                        && reportedAt.add(file.position().file() + ":"
                                + file.position().line())) {
                    findings.add(EasytrieveSupport.finding(META, file.position(),
                            "表ファイル " + file.name() + " に最大件数がありません。"
                                    + DEFAULT_ENTRIES + " 件を超えると SEARCH で"
                                    + "異常終了します。"));
                }
            }
        }
        return findings;
    }
}

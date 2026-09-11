package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.List;

/**
 * The finding ids the pipeline raises on its own behalf, as SARIF rule descriptors.
 *
 * <p>They are diagnostics of the analysis rather than defects of the asset, so no rule of the
 * catalogue carries them and none of these ever runs — {@link #evaluate} exists only because
 * {@link Rule} asks for it, and {@code commands} is empty so nothing can select one. What they are
 * for is the SARIF {@code tool.driver.rules} table: a result whose id has no descriptor reaches an
 * external reader with no name and no help text.
 */
record PipelineDiagnostics(RuleMeta meta) implements Rule {

    private static final String CATEGORY = "解析";

    /** Every diagnostic id, in the order {@link Finding} declares them. */
    static List<Rule> all() {
        return List.of(
                diagnostic(Finding.PARSE_FAILURE_RULE_ID, "構文解析の失敗", Severity.HIGH,
                        "原始プログラムを解析できませんでした。",
                        "解析できなかった資産には、ほかのどの検査も及びません。",
                        "構文解析が失敗した資産ごとに 1 件を報告します。",
                        "文字コードの指定と、コピー句の探索先を確かめてください。"),
                diagnostic(Decode.DECODE_FAILURE_RULE_ID, "復号の失敗", Severity.HIGH,
                        "指定の文字コードでは資産を読めませんでした。",
                        "復号できない資産は解析の対象から外れます。",
                        "復号が失敗した資産ごとに 1 件を報告します。",
                        "--codepage で正しい文字コードを指定してください。"),
                diagnostic(Finding.JCL_SYNTAX_RULE_ID, "読み取れなかった JCL 文", Severity.MEDIUM,
                        "JCL の文を読み取れず、その文だけを飛ばしました。",
                        "飛ばした文のステップ・DD 文は、呼び出し関係にも一覧にも出ません。",
                        "読み取れなかった文ごとに、その文の立つ行を報告します。",
                        "文の書き方を確かめてください。読めない文はそのまま残ります。"),
                diagnostic(Finding.JCL_DIRECTIVE_RULE_ID, "読み飛ばした指示行", Severity.LOW,
                        "スケジューラーや資産管理の指示行を注記として読み飛ばしました。",
                        "指示行の内容は解析に入りません。",
                        "JCL の文でない指示行ごとに、その行を報告します。",
                        "対処は要りません。指示行の扱いを知らせるだけの記録です。"),
                diagnostic(Finding.SQL_SYNTAX_RULE_ID, "読み切れなかった SQL 文", Severity.MEDIUM,
                        "SQL 文を完全には解析できず、文の種別と表名だけを使いました。",
                        "列の使い方を見る検査は、この文には及びません。",
                        "完全には解析できなかった SQL 文ごとに、その文の立つ行を報告します。",
                        "対処は要りません。検査の及ぶ範囲を知らせる記録です。"));
    }

    private static Rule diagnostic(String id, String name, Severity severity, String summary,
            String rationale, String detection, String remedy) {
        return new PipelineDiagnostics(RuleMeta.named(id, name, CATEGORY)
                .summary(summary)
                .rationale(rationale)
                .detection(detection)
                .remedy(remedy)
                .severity(severity)
                .build());
    }

    @Override
    public List<Finding> evaluate(AnalysisContext ctx) {
        return List.of();
    }
}

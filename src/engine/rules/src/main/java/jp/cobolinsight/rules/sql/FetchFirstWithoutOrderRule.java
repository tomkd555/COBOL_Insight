package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * R081 FETCH FIRST n ROWS ONLY with no ORDER BY. Without an order the rows Db2 returns first are
 * whichever the access path reaches first, and that changes with a REORG, a RUNSTATS or a rebind.
 * A program that takes "the latest" or "the smallest" this way is right until the access path
 * changes, and then wrong with no error at all.
 */
public final class FetchFirstWithoutOrderRule implements Rule {

    private static final Pattern FETCH_FIRST =
            Pattern.compile("\\bFETCH\\s+FIRST\\s+(\\S+\\s+)?ROWS?\\s+ONLY\\b");

    private static final RuleMeta META = RuleMeta
            .named("R081", "ORDER BY のない FETCH FIRST", RuleCategory.INTEGRITY)
            .technology(Technology.DB2)
            .symptom("アクセス経路の変化による取得行の変化")
            .summary("FETCH FIRST n ROWS ONLY を指定しながら ORDER BY を持たない問い合わせを"
                    + "検出します。")
            .rationale("順序を指定しないと、先頭に返る行はアクセス経路で決まります。"
                    + "REORG・RUNSTATS・REBIND の後で、エラーにならないまま別の行が返ります。")
            .detection("SELECT・SELECT INTO・カーソル宣言のうち、FETCH FIRST … ROWS ONLY を持ち"
                    + "ORDER BY を持たないものを検出します。選択列が集約関数だけの文と、"
                    + "行の存在だけを確かめる定数 1 つの選択列（SELECT 1 など）は、"
                    + "どの行でも結果が変わらないため対象外です。")
            .remedy("取得したい行が先頭になる ORDER BY を書いてください。")
            .example("""
                    EXEC SQL SELECT TANKA INTO :WS-TANKA FROM TANKA_RIREKI
                        WHERE SHOHIN_CD = :WS-CD FETCH FIRST 1 ROW ONLY END-EXEC.
                    """, """
                    EXEC SQL SELECT TANKA INTO :WS-TANKA FROM TANKA_RIREKI
                        WHERE SHOHIN_CD = :WS-CD
                        ORDER BY TEKIYO_BI DESC FETCH FIRST 1 ROW ONLY END-EXEC.
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL, AssetKind.EASYTRIEVE, AssetKind.SQL)
            .needs(Needs.SQL)
            .build();

    private static final Pattern AGGREGATE_OR_CONSTANT = Pattern.compile(
            "^(?:(?:COUNT|SUM|MIN|MAX|AVG)\\s*\\(.*\\)|\\d+|'')(?:\\s+AS\\s+\\S+)?$");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (SqlStatementModel statement : context.sqlStatements()) {
            if (!statement.isFullyAnalysed() || statement.hasOrderBy()
                    || (statement.kind() != SqlStatementKind.SELECT
                            && statement.kind() != SqlStatementKind.SELECT_INTO
                            && statement.kind() != SqlStatementKind.DECLARE_CURSOR)) {
                continue;
            }
            String text = SqlText.of(statement);
            if (!FETCH_FIRST.matcher(text).find() || text.contains(" ORDER BY ")) {
                continue;
            }
            boolean orderFree = !statement.selectList().isEmpty() && statement.selectList()
                    .stream().allMatch(item -> AGGREGATE_OR_CONSTANT.matcher(
                            item.replaceAll("'[^']*'", "''").toUpperCase(Locale.ROOT).strip())
                            .matches());
            if (!orderFree) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "FETCH FIRST で行数を絞っていますが ORDER BY がありません。"
                                + "どの行が返るかはアクセス経路で決まり、"
                                + "REORG や REBIND の後で変わります。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}

package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * R077 A SELECT INTO or FETCH whose host variables do not match the columns in number. With fewer
 * variables than columns the trailing columns are dropped and only a warning flag says so; with
 * more, the trailing variables keep what the previous row left in them. Either way the statement
 * ends with SQLCODE 0 and the program carries on with fields that belong to no row.
 *
 * <p>The mismatch appears when a column is added to a cursor's select list and one of its FETCH
 * statements is missed. A single target is not compared: it may be a host structure that stands
 * for every column at once.
 */
public final class IntoCountMismatchRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R077", "選択列とホスト変数の個数の不一致", RuleCategory.INTEGRITY)
            .technology(Technology.DB2)
            .symptom("列の受け取り漏れ（SQLWARN3）・前の行の値の残留")
            .summary("SELECT INTO・FETCH INTO の INTO に並ぶホスト変数の個数が、選択列の個数と"
                    + "合わないものを検出します。")
            .rationale("ホスト変数が少なければ後ろの列が捨てられ、多ければ後ろのホスト変数に"
                    + "前の行の値が残ります。どちらも SQLCODE は 0 のままで、"
                    + "誤った値のまま処理が進みます。")
            .detection("SELECT INTO は自身の選択列と、FETCH は同じプログラム（取り込んだコピー句を"
                    + "含む）の同名の DECLARE CURSOR の選択列と、INTO のホスト変数の個数を"
                    + "比べます。選択列に * を含む文、INTO のホスト変数が 1 つだけの文"
                    + "（ホスト構造体の可能性があるため）、完全に解析できなかった文、"
                    + "動的 SQL のカーソルは対象外です。")
            .remedy("選択列と INTO のホスト変数を同じ個数・同じ順に揃えてください。")
            .example("""
                    EXEC SQL DECLARE C1 CURSOR FOR
                        SELECT SHOHIN_CD, SHOHIN_MEI, TANKA FROM SHOHIN END-EXEC.
                    EXEC SQL FETCH C1 INTO :WS-CD, :WS-MEI END-EXEC.
                    """, """
                    EXEC SQL FETCH C1 INTO :WS-CD, :WS-MEI, :WS-TANKA END-EXEC.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .needs(Needs.SQL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            List<SqlStatementModel> own =
                    SqlAdviceSupport.statementsOf(model, context.sqlStatements());
            Map<String, List<String>> selectListByCursor = new HashMap<>();
            List<SqlStatementModel> declarations = new ArrayList<>(own);
            declarations.addAll(SqlAdviceSupport.copiedStatements(model,
                    context.sqlStatements()));
            for (SqlStatementModel statement : declarations) {
                if (statement.kind() == SqlStatementKind.DECLARE_CURSOR
                        && statement.isFullyAnalysed() && statement.cursorName().isPresent()) {
                    selectListByCursor.putIfAbsent(
                            statement.cursorName().get().toUpperCase(Locale.ROOT),
                            statement.selectList());
                }
            }
            for (SqlStatementModel statement : own) {
                if (!statement.isFullyAnalysed() || statement.intoTargets().size() < 2) {
                    continue;
                }
                List<String> columns = switch (statement.kind()) {
                    case SELECT_INTO -> statement.selectList();
                    case FETCH -> selectListByCursor.getOrDefault(statement.cursorName()
                            .map(name -> name.toUpperCase(Locale.ROOT)).orElse(""), List.of());
                    default -> List.of();
                };
                if (columns.isEmpty() || columns.stream().anyMatch(c -> c.strip().endsWith("*"))
                        || columns.size() == statement.intoTargets().size()) {
                    continue;
                }
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        (statement.kind() == SqlStatementKind.FETCH
                                ? "カーソル " + statement.cursorName().orElse("") + " の FETCH"
                                : "SELECT INTO")
                                + " は選択列が " + columns.size() + " 個、ホスト変数が "
                                + statement.intoTargets().size() + " 個です。"
                                + (columns.size() > statement.intoTargets().size()
                                        ? "後ろの列が受け取られません。"
                                        : "後ろのホスト変数に値が設定されません。"),
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}

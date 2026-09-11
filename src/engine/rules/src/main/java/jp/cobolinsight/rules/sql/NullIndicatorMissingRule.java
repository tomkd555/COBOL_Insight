package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.sql.Db2Schema.Column;
import jp.cobolinsight.rules.sql.Db2Schema.Pair;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R037 A nullable column received without an indicator. Lines the select list of a SELECT INTO or
 * of the cursor behind a FETCH up with the INTO host variables, and reports a column whose DCLGEN
 * DECLARE TABLE entry carries no NOT NULL where the receiving host variable has no indicator.
 * Db2 then fails the statement with SQLCODE -305 and leaves the host variable as it was.
 */
public final class NullIndicatorMissingRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R037", "NULL になり得る列を受けるホスト変数の標識指定漏れ", "SQL")
            .summary("NULL になり得る列を、標識の指定がないホスト変数で受け取る"
                    + "埋込みSQL文を検出します。")
            .rationale("NULL の行に当たると SQLCODE が -305 になり、"
                    + "受け取り側項目が更新されないまま処理が進みます。")
            .detection("SELECT INTO と FETCH INTO の INTO の並びを DECLARE TABLE の列と"
                    + "順に突き合わせ、NOT NULL のない列を標識の指定がないホスト変数で"
                    + "受けるものを検出します。DECLARE TABLE がフォルダにない表と、"
                    + "選択項目が式のものは対象外です。")
            .remedy(":ホスト変数 :標識 の形で標識を指定し、"
                    + "標識が負のときは、その標識が付く列を NULL として扱ってください。")
            .example("""
                    EXEC SQL
                        SELECT KOSHIN_YMD INTO :WS-KOSHIN-YMD
                          FROM FLDB.KEIYAKU WHERE KEIYAKU_NO = :WS-KEIYAKU-NO
                    END-EXEC.
                    """, """
                    EXEC SQL
                        SELECT KOSHIN_YMD INTO :WS-KOSHIN-YMD :WS-KOSHIN-YMD-IND
                          FROM FLDB.KEIYAKU WHERE KEIYAKU_NO = :WS-KEIYAKU-NO
                    END-EXEC.
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
            List<EmbeddedBlock> blocks = Db2Schema.sqlBlocks(model);
            Map<String, List<Column>> tables = Db2Schema.tables(blocks);
            if (tables.isEmpty()) {
                continue;
            }
            Map<String, String> cursors = Db2Schema.cursorQueries(blocks);
            for (EmbeddedBlock block : blocks) {
                evaluate(model, block, tables, cursors, findings);
            }
        }
        return findings;
    }

    private static void evaluate(CobolSemanticModel model, EmbeddedBlock block,
            Map<String, List<Column>> tables, Map<String, String> cursors, List<Finding> findings) {
        Set<String> columns = new LinkedHashSet<>();
        String host = null;
        for (Pair pair : Db2Schema.pairs(Db2Schema.body(block.text()), tables, cursors)) {
            if (pair.column().nullable() && pair.target().indicator() == null) {
                columns.add(pair.column().name());
                if (host == null) {
                    host = pair.target().host();
                }
            }
        }
        if (columns.isEmpty()) {
            return;
        }
        findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                host + " に標識の指定がありません。NULL になり得る列 "
                        + String.join("、", columns)
                        + " を受けるため、NULL の行で値が更新されません。",
                new SourcePosition(model.sourceFile(), Db2Schema.lineOf(block, host), 1,
                        SourcePosition.UNKNOWN_BYTE_OFFSET)));
    }
}

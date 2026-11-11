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
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * R076 An SQL COMMIT or ROLLBACK in a program that runs under CICS or IMS. There the transaction
 * manager owns the unit of work: Db2 refuses the statement, and the update the program meant to
 * make permanent stays uncommitted until the task ends, or is backed out with it. The commit has
 * to be asked of the monitor — {@code EXEC CICS SYNCPOINT}, or a DL/I CHKP or ROLB call.
 *
 * <p>{@code ROLLBACK TO SAVEPOINT} undoes part of the unit of work without ending it, which both
 * monitors allow, so it is not reported.
 */
public final class CommitUnderMonitorRule implements Rule {

    private static final Set<String> DLI_CALLS = Set.of("CBLTDLI", "AIBTDLI", "CEETDLI");

    private static final RuleMeta META = RuleMeta
            .named("R076", "CICS・IMS のプログラムでの SQL COMMIT・ROLLBACK", RuleCategory.ABEND)
            .technology(Technology.DB2)
            .symptom("SQLCODE -925・-926")
            .summary("EXEC CICS か DL/I 呼び出しを持つプログラムの、EXEC SQL COMMIT と"
                    + "EXEC SQL ROLLBACK を検出します。")
            .rationale("CICS と IMS では、作業単位はトランザクションマネージャーが管理します。"
                    + "Db2 は SQL の COMMIT・ROLLBACK を拒否し、確定したつもりの更新が"
                    + "タスクの終了まで確定しません。")
            .detection("EXEC CICS のコマンドを持つプログラムと、CBLTDLI・AIBTDLI・CEETDLI を"
                    + "CALL するプログラムについて、埋込みSQLの COMMIT 文と ROLLBACK 文を"
                    + "検出します。作業単位を終わらせない ROLLBACK TO SAVEPOINT は対象外です。")
            .remedy("CICS では EXEC CICS SYNCPOINT・SYNCPOINT ROLLBACK を、IMS では DL/I の"
                    + "CHKP・ROLB 呼び出しを使ってください。")
            .example("""
                    EXEC CICS READ FILE('ZAIKO') INTO(WS-REC) RIDFLD(WS-KEY) END-EXEC.
                    EXEC SQL COMMIT END-EXEC.
                    """, """
                    EXEC CICS READ FILE('ZAIKO') INTO(WS-REC) RIDFLD(WS-KEY) END-EXEC.
                    EXEC CICS SYNCPOINT END-EXEC.
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
            boolean cics = model.embeddedBlocks().stream().anyMatch(b -> b.kind().isCics());
            boolean ims = model.calls().stream().anyMatch(call ->
                    DLI_CALLS.contains(call.target().toUpperCase(Locale.ROOT)));
            if (!cics && !ims) {
                continue;
            }
            for (SqlStatementModel statement
                    : SqlAdviceSupport.statementsOf(model, context.sqlStatements())) {
                boolean commits = statement.kind() == SqlStatementKind.COMMIT;
                boolean rollsBack = statement.kind() == SqlStatementKind.ROLLBACK
                        && !SqlText.of(statement).contains("TO SAVEPOINT");
                if (!commits && !rollsBack) {
                    continue;
                }
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        model.programId() + " は " + (cics ? "CICS" : "IMS")
                                + " のもとで動きますが、SQL の "
                                + (commits ? "COMMIT" : "ROLLBACK") + " を実行しています。"
                                + "Db2 はこの文を拒否し（SQLCODE "
                                + (commits ? "-925" : "-926") + "）、作業単位は"
                                + (commits ? "確定しません。" : "取り消されません。"),
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}

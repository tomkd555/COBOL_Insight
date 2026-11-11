package jp.cobolinsight.rules.jcl;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * R072 A program with embedded SQL executed straight from {@code EXEC PGM=}. A batch Db2 program
 * reaches Db2 through an attachment: the DSN command processor under {@code IKJEFT01}
 * ({@code RUN PROGRAM(…) PLAN(…)}), an IMS region, or the call attachment facility the program
 * itself drives. Run as a plain step, a program built for the first has no thread and no plan, and
 * its first SQL statement fails.
 *
 * <p>A program that calls {@code DSNALI} or {@code DSNRLI} attaches on its own and is not
 * reported. One link-edited with the call attachment stub for an implicit connection states that
 * nowhere in its source and is reported; the rule's text says so.
 */
public final class Db2ProgramWithoutAttachRule implements Rule {

    private static final Set<String> ATTACH_CALLS = Set.of("DSNALI", "DSNRLI", "DSNULI");

    private static final RuleMeta META = RuleMeta
            .named("R072", "Db2 に接続せずに実行される SQL プログラム", RuleCategory.ABEND)
            .technology(Technology.DB2)
            .symptom("SQLCODE -927・-924、異常終了 S04E")
            .summary("埋込みSQLを持つプログラムを、IKJEFT01 の DSN RUN も IMS も経由せず、"
                    + "EXEC PGM= で直接実行しているステップを検出します。")
            .rationale("バッチのプログラムは、DSN コマンド・IMS 領域・呼び出し接続機能の"
                    + "いずれかを通じて Db2 に接続します。直接実行するとスレッドもプランもなく、"
                    + "最初の SQL 文が失敗します。")
            .detection("EXEC PGM= の対象が解析対象の COBOL プログラムで、そのプログラムが"
                    + "実行される埋込みSQL文を持ち、DSNALI・DSNRLI を CALL していないものを"
                    + "検出します。EXEC CICS を持つプログラムは対象外です。"
                    + "接続機能のスタブをリンクして暗黙に接続する構成は原始プログラムから"
                    + "読み取れないため、その構成のプログラムも検出されます。")
            .remedy("EXEC PGM=IKJEFT01 にして、SYSTSIN に DSN SYSTEM(…) と"
                    + "RUN PROGRAM(…) PLAN(…) を書いてください。")
            .example("""
                    //STEP010  EXEC PGM=URI010
                    """, """
                    //STEP010  EXEC PGM=IKJEFT01
                    //SYSTSPRT DD SYSOUT=*
                    //SYSTSIN  DD *
                     DSN SYSTEM(DB2P)
                     RUN PROGRAM(URI010) PLAN(URIPLAN)
                     END
                    /*
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.JCL, AssetKind.COBOL)
            .needs(Needs.SEMANTIC)
            .build();

    /** The statements a precompiler resolves and Db2 never runs: they need no connection. */
    private static final Pattern DECLARATIVE = Pattern.compile(
            "(?is)^\\s*(?:EXEC\\s+SQL\\s+)?(INCLUDE|DECLARE\\s+\\S+\\s+TABLE|BEGIN\\s+DECLARE"
                    + "|END\\s+DECLARE|WHENEVER|DECLARE\\s+\\S+\\s+(?:\\w+\\s+)*CURSOR)\\b");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        Map<String, CobolSemanticModel> sqlPrograms = new HashMap<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            boolean runsSql = model.embeddedBlocks().stream()
                    .anyMatch(block -> block.kind() == EmbeddedBlockKind.SQL
                            && !DECLARATIVE.matcher(block.text()).find());
            boolean cics = model.embeddedBlocks().stream()
                    .anyMatch(block -> block.kind().isCics());
            boolean attaches = model.calls().stream()
                    .anyMatch(call -> ATTACH_CALLS.contains(JclSupport.upper(call.target())));
            if (runsSql && !cics && !attaches) {
                sqlPrograms.putIfAbsent(JclSupport.upper(model.programId()), model);
            }
        }
        List<Finding> findings = new ArrayList<>();
        JclSupport.Findings report = new JclSupport.Findings(META, findings);
        for (JclJobModel job : context.jclJobs()) {
            for (JclStep step : JclSupport.programSteps(job)) {
                CobolSemanticModel model = sqlPrograms.get(JclSupport.upper(step.target()));
                if (model != null) {
                    report.add(step.position(), step.name() + " は埋込みSQLを持つ "
                            + model.programId() + " を EXEC PGM= で直接実行しています。"
                            + "Db2 に接続されないため、最初の SQL 文が失敗します。");
                }
            }
        }
        return findings;
    }
}

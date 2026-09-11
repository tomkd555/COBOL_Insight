package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.jcl.JclUtilityFacts;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.FileDefinition;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * R052 A DD the program opens that the step running it does not allocate. Each step is matched to
 * the programs it runs — the target of an {@code EXEC PGM} and every program its own control cards
 * run ({@code CALL} under IKJEFT01, {@code RUN PROGRAM} under DSNUTILB) — and each of those
 * programs' FILE-CONTROL entries is looked up among the DD statements of the step. A DD name the
 * step never writes, and that no TSO {@code ALLOCATE} of the step's cards names either, leaves the
 * OPEN failing.
 *
 * <p>Only a file the program opens is looked for. A SELECT with no OPEN behind it — the sort work
 * file of a COBOL SORT or MERGE is the common one, an SD the program never opens and the sort
 * serves through SORTWKnn — names a DD the step has no reason to carry, so a FileDefinition with no
 * access recorded is left alone.
 *
 * <p>Reported at the SELECT entry of the program, because that is the name the fix has to line up
 * with, and once per entry however many steps omit it — a DD expanded from a PROC would otherwise
 * be reported once per job that calls it. The message names every step that omits the DD, so the
 * one finding still says how much work the fix is. JOBLIB and STEPLIB are left out: they are the
 * library concatenation, not a file the program opens.
 */
public final class JclMissingDdForProgramRule implements Rule {

    private static final Set<String> LIBRARIES = Set.of("JOBLIB", "STEPLIB");

    private static final RuleMeta META =
            RuleMeta.named("R052", "プログラムが開く DD の割り当て漏れ", "JCL制御")
            .summary("プログラムの SELECT 文が指す DD 名を、"
                    + "そのプログラムを実行するステップが割り当てていないものを検出します。")
            .rationale("OPEN が失敗し、"
                    + "そのファイルを使う処理が始まりません。")
            .detection("EXEC PGM のプログラムと、ステップの制御文が実行するプログラムについて、"
                    + "FILE-CONTROL の ASSIGN 句が指す DD 名がステップの DD 文にないものを"
                    + "検出します。プログラムが OPEN しないファイルは対象外です。"
                    + "並べ替えが使う作業ファイルのように、SELECT はあっても OPEN しない"
                    + "ファイルは、ステップが DD 文を持つ必要がありません。"
                    + "TSO の ALLOCATE が割り当てる DD 名も対象外です。"
                    + "JOBLIB・STEPLIB も対象外です。"
                    + "1 つの SELECT 文について報告は 1 件で、"
                    + "DD 文を持たないステップをすべて本文に挙げます。")
            .remedy("ステップに DD 文を足すか、"
                    + "ASSIGN 句の DD 名をステップの DD 名に合わせてください。")
            .example("""
                    //STEP010  EXEC PGM=SYK001
                    //ORDIN    DD DSN=SYKT.ORDER.DAILY,DISP=SHR
                    """, """
                    //STEP010  EXEC PGM=SYK001
                    //ORDIN    DD DSN=SYKT.ORDER.DAILY,DISP=SHR
                    //ORDERR   DD DSN=SYKW.ORDER.ERROR,DISP=(NEW,CATLG,DELETE)
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL, AssetKind.JCL)
            .needs(Needs.SEMANTIC)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        Map<String, CobolSemanticModel> byProgramId = new LinkedHashMap<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            byProgramId.putIfAbsent(model.programId().toUpperCase(Locale.ROOT), model);
        }
        Map<String, Omission> omissions = new LinkedHashMap<>();
        for (JclJobModel job : context.jclJobs()) {
            for (JclStep step : job.steps()) {
                Set<String> provided = provided(step);
                for (String programName : programsOf(step)) {
                    CobolSemanticModel model =
                            byProgramId.get(programName.toUpperCase(Locale.ROOT));
                    if (model == null) {
                        continue;
                    }
                    collect(job, step, model, provided, omissions);
                }
            }
        }
        List<Finding> findings = new ArrayList<>();
        for (Omission omission : omissions.values()) {
            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                    "DD " + omission.ddName() + " を割り当てる DD 文が、" + omission.programId()
                            + " を実行する " + String.join("・", omission.steps())
                            + " にありません。ファイル " + omission.file().fileName()
                            + " の OPEN が失敗します。",
                    omission.file().position()));
        }
        return findings;
    }

    /** One SELECT entry whose DD is missing, and every step that omits it, in the order read. */
    private record Omission(String ddName, String programId, FileDefinition file,
            Set<String> steps) {
    }

    private static void collect(JclJobModel job, JclStep step, CobolSemanticModel model,
            Set<String> provided, Map<String, Omission> omissions) {
        for (FileDefinition file : model.files()) {
            String ddName = file.ddName().map(name -> name.toUpperCase(Locale.ROOT)).orElse(null);
            if (ddName == null || file.accesses().isEmpty() || LIBRARIES.contains(ddName)
                    || provided.contains(ddName)) {
                continue;
            }
            // Keyed by the entry alone: a DD expanded from a PROC keeps its line in the PROC
            // member, and two jobs calling the PROC would otherwise report the same entry twice.
            String key = file.position().file() + ":" + file.position().line();
            omissions.computeIfAbsent(key, ignored -> new Omission(ddName, model.programId(), file,
                    new LinkedHashSet<>())).steps().add(job.jobName() + " の " + step.name());
        }
    }

    /** The DD names the step allocates: its own DD statements, and what its control cards name. */
    private static Set<String> provided(JclStep step) {
        Set<String> names = new LinkedHashSet<>();
        for (JclDdStatement dd : step.ddStatements()) {
            names.add(dd.ddName().toUpperCase(Locale.ROOT));
        }
        step.utility().map(JclUtilityFacts::ddRoles)
                .ifPresent(roles -> roles.keySet()
                        .forEach(name -> names.add(name.toUpperCase(Locale.ROOT))));
        return names;
    }

    /** The programs the step runs: the EXEC PGM target, and every program its cards run. */
    private static List<String> programsOf(JclStep step) {
        List<String> programs = new ArrayList<>();
        if (step.execKind() == JclExecKind.PGM) {
            programs.add(step.target());
        }
        step.utility().ifPresent(utility -> utility.programRuns()
                .forEach(run -> programs.add(run.program())));
        return programs;
    }
}

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * R099 An Easytrieve file the step that runs the program allocates no DD for. The file name of a
 * FILE statement is the DD name; a file the program reads or writes with no DD of that name fails
 * when it is opened. For a program handed to the compiler in-stream the step is the one it stands
 * in; for a member it is every step that executes the load module of that name.
 *
 * <p>A virtual file, an in-stream table and a database file need no DD, and a file no statement
 * uses is never opened.
 */
public final class FileWithoutDdRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R099", "Easytrieve のファイルに対応する DD の割り当て漏れ", RuleCategory.ABEND)
            .technology(Technology.EASYTRIEVE)
            .symptom("OPEN 失敗（DD STATEMENT MISSING）")
            .summary("Easytrieve のプログラムが読み書きするファイルの DD を、そのプログラムを"
                    + "実行するステップが割り当てていないものを検出します。")
            .rationale("FILE 文のファイル名が DD 名です。DD がないとファイルを開けず、"
                    + "プログラムが異常終了します。")
            .detection("JCL のストリーム内に書かれたプログラムはそのステップと、メンバーとして"
                    + "置かれたプログラムは EXEC PGM= で同じ名前を実行するすべてのステップと、"
                    + "プログラムが GET・PUT・READ・WRITE・JOB INPUT・SORT・SEARCH などで使う"
                    + "ファイルの名前を DD 名と突き合わせます。VIRTUAL・SQL・DLI・IDMS のファイル、"
                    + "ストリーム内の表、どの文も使わないファイルは対象外です。")
            .remedy("ステップに FILE 文のファイル名と同じ名前の DD 文を追加してください。")
            .example("""
                    //STEP010 EXEC PGM=EZTPA00
                    //URIAGE  DD DSN=PROD.URIAGE.DAILY,DISP=SHR
                    //SYSIN   DD *
                    FILE URIAGE FB(100 1000)
                    FILE MASTER VS
                    JOB INPUT URIAGE
                      READ MASTER KEY U-TEN STATUS
                    """, """
                    //STEP010 EXEC PGM=EZTPA00
                    //URIAGE  DD DSN=PROD.URIAGE.DAILY,DISP=SHR
                    //MASTER  DD DSN=PROD.TENPO.KSDS,DISP=SHR
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT)
            .targets(AssetKind.EASYTRIEVE, AssetKind.JCL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        // The DD names of every step, by what the step runs: job.step for an in-stream program,
        // the EXEC target for a load module.
        Map<String, List<StepDds>> byProgram = new LinkedHashMap<>();
        context.jclJobs().forEach(job -> job.steps().forEach(step -> {
            Set<String> ddNames = new HashSet<>();
            step.ddStatements().forEach(dd -> ddNames.add(dd.ddName().toUpperCase(Locale.ROOT)));
            StepDds dds = new StepDds(job.jobName() + " の " + step.name(), ddNames);
            byProgram.computeIfAbsent(job.jobName() + "." + step.name(),
                    key -> new ArrayList<>()).add(dds);
            byProgram.computeIfAbsent(step.target().toUpperCase(Locale.ROOT),
                    key -> new ArrayList<>()).add(dds);
        }));
        List<Finding> findings = new ArrayList<>();
        for (EasytrieveProgram program : EasytrieveSupport.programs(context)) {
            List<StepDds> steps = byProgram.getOrDefault(program.name(), List.of());
            for (EasytrieveFile file : program.files()) {
                if (!file.needsDd() || !program.fileAccess().containsKey(file.name())) {
                    continue;
                }
                List<String> missing = steps.stream()
                        .filter(step -> !step.ddNames().contains(file.name()))
                        .map(StepDds::label).distinct().toList();
                if (!missing.isEmpty()) {
                    findings.add(EasytrieveSupport.finding(META, file.position(),
                            "ファイル " + file.name() + " を割り当てる DD 文が、"
                                    + String.join("・", missing) + " にありません。"
                                    + "ファイルを開けずに異常終了します。"));
                }
            }
        }
        return findings;
    }

    private record StepDds(String label, Set<String> ddNames) {
    }
}

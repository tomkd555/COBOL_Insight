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
import jp.cobolinsight.core.semantic.FileAccess;
import jp.cobolinsight.core.semantic.FileDefinition;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * R071 A sequential file a program opens for output, allocated {@code DISP=SHR}. {@code SHR} lets
 * every other job read the data set, and write it, while this one rewrites it: a reader sees a
 * half-written file, and two writers leave whatever the last one closed. The DISP is what
 * serialises a sequential data set, and only {@code OLD}, {@code NEW} or {@code MOD} does.
 *
 * <p>An indexed or relative file is left out — VSAM serialises through its share options — and so
 * is {@code OPEN I-O}, which the same rule would report for every VSAM update program.
 */
public final class SharedOutputFileRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R071", "出力で開く順ファイルへの DISP=SHR", RuleCategory.INTEGRITY)
            .technology(Technology.JCL)
            .symptom("書き込み中のファイルの同時参照・同時更新")
            .summary("プログラムが OPEN OUTPUT・EXTEND で開く順ファイルの DD に、"
                    + "DISP=SHR を指定しているものを検出します。")
            .rationale("SHR は他のジョブの同時使用を許します。書き込み中のファイルを他のジョブが"
                    + "読むか、2 つのジョブが同時に書き、最後に閉じた側の内容だけが残ります。")
            .detection("EXEC PGM のプログラムと、ステップの制御文・PARM が実行するプログラムについて、"
                    + "FILE-CONTROL の ASSIGN 句が指す DD のうち、プログラムが OPEN OUTPUT・"
                    + "EXTEND で開き、ステップの DD 文の DISP の状態が SHR のものを検出します。"
                    + "ORGANIZATION が INDEXED・RELATIVE のファイル、OPEN I-O で開くファイル、"
                    + "DUMMY・SYSOUT の DD は対象外です。")
            .remedy("既存のデータセットへ書くなら DISP=OLD、追記なら DISP=MOD を"
                    + "指定してください。")
            .example("""
                    //STEP010 EXEC PGM=URI010
                    //OUTFILE DD DSN=PROD.URIAGE.SYUKEI,DISP=SHR
                    """, """
                    //STEP010 EXEC PGM=URI010
                    //OUTFILE DD DSN=PROD.URIAGE.SYUKEI,DISP=OLD
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.JCL, AssetKind.COBOL)
            .needs(Needs.SEMANTIC)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        Map<String, CobolSemanticModel> byProgramId = new HashMap<>();
        context.cobolPrograms().forEach(model ->
                byProgramId.putIfAbsent(JclSupport.upper(model.programId()), model));
        List<Finding> findings = new ArrayList<>();
        JclSupport.Findings report = new JclSupport.Findings(META, findings);
        for (JclJobModel job : context.jclJobs()) {
            for (JclStep step : job.steps()) {
                for (String program : JclSupport.programsOf(step)) {
                    CobolSemanticModel model = byProgramId.get(program);
                    if (model == null) {
                        continue;
                    }
                    for (FileDefinition file : model.files()) {
                        if (!writesSequentially(file)) {
                            continue;
                        }
                        JclSupport.dd(step, file.ddName().orElse(""))
                                .filter(dd -> !dd.dummy() && dd.sysout().isEmpty()
                                        && dd.dataset().isPresent()
                                        && dd.dispositionText().isPresent()
                                        && JclSupport.status(dd).equals("SHR"))
                                .ifPresent(dd -> report.add(dd.position(), step.name()
                                        + " の DD " + dd.ddName() + " は DISP=SHR ですが、"
                                        + model.programId() + " はファイル " + file.fileName()
                                        + " を出力で開きます。書き込み中のデータセットを"
                                        + "他のジョブが同時に使えます。"));
                    }
                }
            }
        }
        return findings;
    }

    private static boolean writesSequentially(FileDefinition file) {
        String organisation = file.organisation().orElse("").toUpperCase(Locale.ROOT);
        boolean sequential = organisation.isEmpty() || organisation.contains("SEQUENTIAL");
        return sequential && file.ddName().isPresent()
                && (file.accesses().contains(FileAccess.OUTPUT)
                        || file.accesses().contains(FileAccess.EXTEND));
    }
}

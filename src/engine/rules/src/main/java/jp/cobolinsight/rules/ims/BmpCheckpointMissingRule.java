package jp.cobolinsight.rules.ims;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.cfg.CfgSupport;
import jp.cobolinsight.rules.cobol.ServiceCalls;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * R091 A program run as a BMP that changes the database and never takes a checkpoint. A BMP
 * shares the online databases: every segment it changes stays locked until it commits, and without
 * a CHKP call that is the end of the job. The online transactions queue behind its locks, the
 * dynamic log fills, and an abend at the last record backs out the whole run.
 *
 * <p>Which programs are BMPs is read from the JCL: the region type in the PARM of
 * {@code DFSRRC00}. What a program does is read from the DL/I function codes it holds.
 */
public final class BmpCheckpointMissingRule implements Rule {

    private static final Set<String> UPDATES = Set.of("ISRT", "REPL", "DLET");
    private static final Set<String> CHECKPOINTS = Set.of("CHKP", "SYMCHKP", "XRST");

    private static final RuleMeta META = RuleMeta
            .named("R091", "チェックポイントを取らない BMP の更新プログラム",
                    RuleCategory.OPERATION)
            .technology(Technology.IMS)
            .symptom("オンラインのロック待ち・異常終了時の全件バックアウト")
            .summary("BMP として実行され、データベースを更新するのに CHKP 呼び出しを持たない"
                    + "プログラムを検出します。")
            .rationale("BMP はオンラインと同じデータベースを使い、更新したセグメントのロックを"
                    + "コミットまで保持します。チェックポイントがないとジョブの終わりまで"
                    + "ロックが残り、オンラインの処理が待たされ、異常終了時は全件が"
                    + "バックアウトされます。")
            .detection("JCL の DFSRRC00 の PARM が BMP で起動するプログラムのうち、"
                    + "ISRT・REPL・DLET の機能コードを持ち、CHKP・XRST の機能コードを"
                    + "持たないものを検出します。機能コードは、データ項目の VALUE 句と"
                    + "手続き部の定数から読み取ります。報告はプログラムにつき 1 件で、"
                    + "最初の DL/I 呼び出しの位置に出します。")
            .remedy("一定の件数ごとに CHKP 呼び出しを出し、再始動のために XRST を"
                    + "組み込んでください。")
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL, AssetKind.JCL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        Set<String> bmpPrograms = new LinkedHashSet<>();
        context.jclJobs().forEach(job -> job.steps().forEach(step -> step.utility()
                .ifPresent(facts -> facts.programRuns().forEach(run -> {
                    if (run.environment().equals("IMS-BMP")) {
                        bmpPrograms.add(run.program().toUpperCase(Locale.ROOT));
                    }
                }))));
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            if (!bmpPrograms.contains(model.programId().toUpperCase(Locale.ROOT))) {
                continue;
            }
            Set<String> codes = new LinkedHashSet<>();
            ServiceCalls.literalValues(model).forEach(value -> codes.add(value.strip()));
            String text = ServiceCalls.programText(model);
            boolean updates = UPDATES.stream().anyMatch(code -> codes.contains(code)
                    || text.contains("'" + code));
            boolean checkpoints = CHECKPOINTS.stream().anyMatch(code -> codes.contains(code)
                    || text.contains("'" + code));
            if (!updates || checkpoints) {
                continue;
            }
            ServiceCalls.all(model).stream()
                    .filter(call -> call.target().endsWith("TDLI")).findFirst()
                    .ifPresent(call -> findings.add(Finding.of(META.id(),
                            META.defaultSeverity().toLevel(),
                            model.programId() + " は BMP として実行されデータベースを"
                                    + "更新しますが、CHKP 呼び出しがありません。"
                                    + "更新のロックがジョブの終わりまで残ります。",
                            CfgSupport.at(call.statement().range()))));
        }
        return findings;
    }
}

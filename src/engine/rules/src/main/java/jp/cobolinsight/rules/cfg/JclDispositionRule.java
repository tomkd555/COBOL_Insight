package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * R032 A new dataset kept after an abend. A DD that allocates a dataset (DISP status NEW, or
 * omitted, which means NEW) and catalogues or keeps it on normal termination, but names no
 * abnormal-termination disposition or names CATLG/KEEP there, leaves the dataset behind when the
 * step abends. The rerun then fails on the duplicate name before it does any work.
 */
public final class JclDispositionRule implements Rule {

    private static final Set<String> KEPT = Set.of("CATLG", "KEEP");

    private static final RuleMeta META =
            RuleMeta.named("R032", "JCL 新規データセットの異常終了時の処分未指定", "JCL制御")
            .summary("新規に割り当てるデータセットの DISP で、異常終了時の処分が未指定か"
                    + "CATLG・KEEP になっているものを検出します。")
            .rationale("ステップが異常終了するとデータセットがカタログされたまま残り、"
                    + "再実行が同名データセットの割り当てで失敗します。")
            .detection("DD 文の DISP の状態が NEW（省略を含む）で、正常終了時の処分が"
                    + "CATLG・KEEP、異常終了時の処分が未指定か CATLG・KEEP のものを検出します。"
                    + "OLD・SHR・MOD のデータセットは対象外です。")
            .remedy("DISP=(NEW,CATLG,DELETE) のように、異常終了時の処分に DELETE を"
                    + "指定してください。")
            .example("""
                    //NYUOUT DD DSN=FLW.D250901.NYUKIN.EDIT,DISP=(NEW,CATLG)
                    """, """
                    //NYUOUT DD DSN=FLW.D250901.NYUKIN.EDIT,DISP=(NEW,CATLG,DELETE)
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.JCL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        // A DD expanded from a PROC keeps its line in the PROC member; two jobs calling the
        // PROC would otherwise report it twice.
        Set<String> reportedAt = new HashSet<>();
        for (JclJobModel job : context.jclJobs()) {
            for (JclStep step : job.steps()) {
                for (JclDdStatement dd : step.ddStatements()) {
                    String disp = dd.dispositionText().filter(JclDispositionRule::keptAfterAbend)
                            .orElse(null);
                    if (disp == null || !reportedAt.add(
                            dd.position().file() + ":" + dd.position().line())) {
                        continue;
                    }
                    findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                            step.name() + " の DD " + dd.ddName() + " の DISP=" + disp
                                    + " は、異常終了時にデータセットを残します。"
                                    + "再実行が同名データセットの割り当てで失敗します。",
                            dd.position()));
                }
            }
        }
        return findings;
    }

    /** DISP=(NEW|,CATLG|KEEP[,CATLG|KEEP]): the dataset survives an abend of the step. */
    static boolean keptAfterAbend(String disposition) {
        String text = disposition.trim().toUpperCase(Locale.ROOT);
        if (text.startsWith("(") && text.endsWith(")")) {
            text = text.substring(1, text.length() - 1);
        }
        String[] parts = text.split(",", -1);
        String status = parts[0].trim();
        String normal = parts.length > 1 ? parts[1].trim() : "";
        String abnormal = parts.length > 2 ? parts[2].trim() : "";
        boolean allocates = status.isEmpty() || status.equals("NEW");
        return allocates && KEPT.contains(normal) && (abnormal.isEmpty() || KEPT.contains(abnormal));
    }
}

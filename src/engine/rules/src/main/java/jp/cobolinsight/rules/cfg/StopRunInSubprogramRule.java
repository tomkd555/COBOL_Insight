package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.callgraph.CallGraph;
import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.callgraph.EdgeKind;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * R107 STOP RUN in a program other programs call. STOP RUN ends the run unit, not the program: the
 * caller never gets control back, so the statements after its CALL — the CLOSE of its files, the
 * COMMIT, the totals it prints at the end — do not run. The job step ends with return code 0 and
 * an output file that was never closed.
 *
 * <p>A called program returns with GOBACK or EXIT PROGRAM. The same program may also be the main
 * program of another step, where STOP RUN is right; GOBACK is right in both places.
 */
public final class StopRunInSubprogramRule implements Rule {

    private static final String PROGRAM_PREFIX = "program:";

    private static final Pattern STOP_RUN = Pattern.compile("(?is)^STOP\\s+RUN\\b");

    private static final RuleMeta META = RuleMeta
            .named("R107", "呼び出されるプログラムの STOP RUN", RuleCategory.INTEGRITY)
            .technology(Technology.COBOL)
            .symptom("呼び出し元の後続処理の不実行（CLOSE・COMMIT の欠落）")
            .summary("他のプログラムから CALL されるプログラムの STOP RUN を検出します。")
            .rationale("STOP RUN は実行単位全体を終了し、呼び出し元へ戻りません。呼び出し元の"
                    + "CALL より後ろにあるファイルの CLOSE、COMMIT、集計の出力が実行されないまま、"
                    + "ステップが正常終了します。")
            .detection("呼び出し関係グラフで、解析対象の他のプログラムから CALL・EXEC CICS LINK で"
                    + "呼び出されているプログラムについて、手続き部の STOP RUN を検出します。"
                    + "どこからも呼び出されないプログラムは対象外です。")
            .remedy("GOBACK に置き換えてください。主プログラムとして実行されたときは"
                    + "実行単位を終了し、呼び出されたときは呼び出し元へ戻ります。")
            .example("""
                    PROCEDURE DIVISION USING LK-PARM.
                        PERFORM KEISAN.
                        STOP RUN.
                    """, """
                    PROCEDURE DIVISION USING LK-PARM.
                        PERFORM KEISAN.
                        GOBACK.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .needs(Needs.CALL_GRAPH)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        CallGraph graph = context.callGraph().orElse(null);
        if (graph == null) {
            return List.of();
        }
        // Called program to one of its callers, for the message.
        Map<String, String> callerOf = new LinkedHashMap<>();
        for (CallGraphEdge edge : graph.edges()) {
            if (edge.kind() == EdgeKind.CALL && edge.fromId().startsWith(PROGRAM_PREFIX)
                    && edge.toId().startsWith(PROGRAM_PREFIX)
                    && !edge.fromId().equals(edge.toId())) {
                callerOf.putIfAbsent(edge.toId().substring(PROGRAM_PREFIX.length()),
                        edge.fromId().substring(PROGRAM_PREFIX.length()));
            }
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            String caller = callerOf.get(model.programId().toUpperCase(Locale.ROOT));
            if (caller == null) {
                continue;
            }
            model.procedures().forEach(procedure ->
                    CfgSupport.walk(procedure.statements(), statement -> {
                        if (statement instanceof SimpleStatement simple
                                && STOP_RUN.matcher(simple.text().strip()).find()) {
                            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                                    model.programId() + " は " + caller + " から呼び出されますが、"
                                            + "STOP RUN で実行単位全体を終了します。"
                                            + "呼び出し元の後続の処理が実行されません。",
                                    CfgSupport.at(statement.range())));
                        }
                    }));
        }
        return findings;
    }
}

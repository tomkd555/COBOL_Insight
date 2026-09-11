package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * R035 A DFHCOMMAREA reference with no EIBCALEN test. On the first start of a transaction CICS
 * passes no communication area, and EIBCALEN is zero; a program that reads DFHCOMMAREA without
 * asking addresses storage that belongs to no one. Only a program a transaction starts can be
 * started that way: one the transaction definition table names (a {@code transaction:} edge of
 * the call graph), or, where no such edge names it, one that talks to the terminal and is not
 * the target of another program's XCTL or START. A LINK target, a CALLed subprogram and an XCTL
 * target whose callers hand it the communication area are left alone.
 */
public final class EibcalenUncheckedRule implements Rule {

    private static final String COMMAREA = "DFHCOMMAREA";
    private static final String TRANSACTION_PREFIX = "transaction:";
    private static final String PROGRAM_PREFIX = "program:";
    private static final Pattern EIBCALEN = CfgSupport.wordPattern("EIBCALEN");

    private static final RuleMeta META =
            RuleMeta.named("R035", "EIBCALEN 未検査の DFHCOMMAREA 参照", "CICS")
                    .summary("EIBCALEN を検査しないまま DFHCOMMAREA を参照する"
                            + "疑似会話プログラムを検出します。")
                    .rationale("初回起動では連絡域が渡されないため、"
                            + "他の処理が使っている領域を読み書きします。")
                    .detection("対象は、トランザクション定義表がトランザクションの起動先とするプログラムか、"
                            + "端末と SEND・RECEIVE でやり取りし、"
                            + "他プログラムの XCTL・START の遷移先ではないプログラムです。"
                            + "そのうち、DFHCOMMAREA かその下の項目を参照し、"
                            + "EIBCALEN をどの条件でも参照しないものを検出します。"
                            + "LINK・CALL の呼び出し先、定義表にない XCTL・START の遷移先、"
                            + "DFHCOMMAREA を宣言しないプログラムは対象外です。")
                    .remedy("DFHCOMMAREA を参照する前に EIBCALEN が 0 かどうかで"
                            + "初回起動と再入を分けてください。")
                    .example("""
                            MOVE DFHCOMMAREA TO WS-COMMAREA
                            """, """
                            IF EIBCALEN = 0
                                PERFORM 1000-SHOKAI
                            ELSE
                                MOVE DFHCOMMAREA TO WS-COMMAREA
                            END-IF
                            """)
                    .severity(Severity.MEDIUM)
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
        Set<String> transactionEntries = transactionEntries(context);
        Set<String> transferTargets = CicsReturnMissingRule.transferTargets(context);
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            String programId = CfgSupport.upper(model.programId());
            boolean participates = transactionEntries.contains(programId)
                    || (CicsReturnMissingRule.talksToTerminal(model)
                            && !transferTargets.contains(programId));
            if (!participates) {
                continue;
            }
            Set<String> names = commareaNames(model);
            if (names.isEmpty() || testsEibcalen(model)) {
                continue;
            }
            Statement first = firstReference(model, names);
            if (first != null) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        COMMAREA + " を EIBCALEN の検査なしに参照しています。"
                                + "連絡域が渡されない起動では領域の内容が定まりません。",
                        new SourcePosition(model.sourceFile(),
                                first.range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
        return findings;
    }

    /** The programs the transaction definition table starts: targets of the call graph's transaction edges. */
    private static Set<String> transactionEntries(AnalysisContext context) {
        Set<String> entries = new LinkedHashSet<>();
        context.callGraph().ifPresent(graph -> {
            for (CallGraphEdge edge : graph.edges()) {
                if (edge.fromId().startsWith(TRANSACTION_PREFIX)
                        && edge.toId().startsWith(PROGRAM_PREFIX)) {
                    entries.add(CfgSupport.upper(edge.toId().substring(PROGRAM_PREFIX.length())));
                }
            }
        });
        return entries;
    }

    /** DFHCOMMAREA and every item declared under it, uppercased. Empty when the program declares none. */
    private static Set<String> commareaNames(CobolSemanticModel model) {
        Set<String> names = new LinkedHashSet<>();
        CfgSupport.itemNamed(model, COMMAREA).ifPresent(item -> {
            names.add(COMMAREA);
            collect(item.children(), names);
        });
        return names;
    }

    private static void collect(List<DataItem> items, Set<String> names) {
        for (DataItem item : items) {
            String name = CfgSupport.upper(item.name());
            if (!name.equals("FILLER")) {
                names.add(name);
            }
            collect(item.children(), names);
        }
    }

    private static boolean testsEibcalen(CobolSemanticModel model) {
        boolean[] tested = {false};
        for (Procedure procedure : model.procedures()) {
            CfgSupport.walk(procedure.statements(), statement -> {
                if (EIBCALEN.matcher(CfgSupport.referenceText(statement)).find()) {
                    tested[0] = true;
                }
            });
        }
        return tested[0];
    }

    /** The first statement, in definition order, that names the communication area or one of its items. */
    private static Statement firstReference(CobolSemanticModel model, Set<String> names) {
        List<Pattern> patterns = names.stream().map(CfgSupport::wordPattern).toList();
        Statement[] first = {null};
        for (Procedure procedure : model.procedures()) {
            CfgSupport.walk(procedure.statements(), statement -> {
                if (first[0] != null) {
                    return;
                }
                String text = CfgSupport.referenceText(statement);
                if (patterns.stream().anyMatch(pattern -> pattern.matcher(text).find())) {
                    first[0] = statement;
                }
            });
            if (first[0] != null) {
                break;
            }
        }
        return first[0];
    }
}

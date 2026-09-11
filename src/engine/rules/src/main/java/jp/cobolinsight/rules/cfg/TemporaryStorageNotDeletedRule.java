package jp.cobolinsight.rules.cfg;

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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R045 A temporary storage queue that no program deletes. A TS queue written under a name nobody
 * issues DELETEQ TS for survives the transaction and stays in the TS data set until an operator
 * removes it; the next run of the transaction then reads items the previous one left. Only a queue
 * named by a literal is followed — a name held in a data item is not comparable across programs —
 * and WRITEQ TD is a different resource.
 */
public final class TemporaryStorageNotDeletedRule implements Rule {

    private static final Pattern WRITEQ_TS = Pattern.compile("^WRITEQ\\s+TS(?![\\p{L}\\p{N}-])");
    private static final Pattern DELETEQ_TS = Pattern.compile("^DELETEQ\\s+TS(?![\\p{L}\\p{N}-])");
    private static final Pattern QUEUE_LITERAL = Pattern.compile(
            "(?i)\\b(?:QUEUE|QNAME)\\s*\\(\\s*(?:'([^']*)'|\"([^\"]*)\")\\s*\\)");

    private static final RuleMeta META =
            RuleMeta.named("R045", "DELETEQ されない一時記憶キュー", "CICS")
                    .summary("どのプログラムも DELETEQ TS で削除しない名前の"
                            + "一時記憶キューへの WRITEQ TS を検出します。")
                    .rationale("キューが一時記憶データセットに残り続け、"
                            + "次の起動でプログラムが前回の項目を読み込みます。")
                    .detection("WRITEQ TS の QUEUE に書かれた文字定数のうち、"
                            + "解析対象のどのプログラムの DELETEQ TS にも現れない名前を"
                            + "検出します。キュー名 1 つにつき、最初の WRITEQ TS で"
                            + "1 件を報告します。"
                            + "キュー名をデータ項目で与えるものと WRITEQ TD は対象外です。")
                    .remedy("キューを使い終える処理で EXEC CICS DELETEQ TS を実行してください。")
                    .example("""
                            EXEC CICS WRITEQ TS QUEUE('FLTSQ001')
                                 FROM(WS-TSQ-REC) LENGTH(100) END-EXEC
                            """, """
                            EXEC CICS WRITEQ TS QUEUE('FLTSQ001')
                                 FROM(WS-TSQ-REC) LENGTH(100) END-EXEC
                            ...
                            EXEC CICS DELETEQ TS QUEUE('FLTSQ001') END-EXEC
                            """)
                    .severity(Severity.LOW)
                    .commands(Command.LINT)
                    .targets(AssetKind.COBOL)
                    .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        Set<String> deleted = new LinkedHashSet<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            for (EmbeddedBlock block : model.embeddedBlocks()) {
                if (DELETEQ_TS.matcher(CfgSupport.cicsBody(block.text())).find()) {
                    queueName(block).ifPresent(deleted::add);
                }
            }
        }
        Set<String> reported = new LinkedHashSet<>();
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            for (EmbeddedBlock block : model.embeddedBlocks()) {
                if (!WRITEQ_TS.matcher(CfgSupport.cicsBody(block.text())).find()) {
                    continue;
                }
                String queue = queueName(block).orElse(null);
                if (queue == null || deleted.contains(queue) || !reported.add(queue)) {
                    continue;
                }
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        queue + " は DELETEQ TS で削除されていません。"
                                + "一時記憶データセットに残り続けます。",
                        new SourcePosition(model.sourceFile(),
                                CfgSupport.operandLine(block, "QUEUE"), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
        return findings;
    }

    /** The queue name when the command writes it as a literal; empty when a data item holds it. */
    private static Optional<String> queueName(EmbeddedBlock block) {
        Matcher matcher = QUEUE_LITERAL.matcher(block.text());
        if (!matcher.find()) {
            return Optional.empty();
        }
        String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return name.isBlank() ? Optional.empty()
                : Optional.of(CfgSupport.upper(name.trim()));
    }
}

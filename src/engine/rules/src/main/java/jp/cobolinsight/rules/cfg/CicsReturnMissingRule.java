package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R022 Pseudo-conversational break due to a missing CICS RETURN. Flags a CICS-participating
 * program that reaches its terminal point without a single EXEC CICS RETURN, since control of
 * the transaction then never returns to CICS. A RETURN with TRANSID continues the
 * pseudo-conversation and a plain RETURN ends it; both hand control back. CICS participation is
 * judged by whether the program itself talks to the terminal (SEND, RECEIVE, CONVERSE, RETURN
 * TRANSID), or is the transfer target of another program's XCTL/START (neither the call graph
 * nor the transaction definition table is used). A LINK target or a CALLed subprogram that only
 * reads files or queues under CICS returns to its caller with GOBACK, so neither is a
 * participant on that ground.
 */
public final class CicsReturnMissingRule implements Rule {

    private static final RuleMeta META =
            RuleMeta.named("R022", "CICS RETURN 文欠如による疑似会話の途絶", "制御フロー")
                    .summary("EXEC CICS RETURN を 1 つも持たないまま終端に達する"
                            + "CICS プログラムを検出します。")
                    .rationale("プログラムが制御を CICS に戻さないため、次の入力を受け付ける状態が"
                            + "作られません。疑似会話が途切れ、端末が応答しなくなります。")
                    .detection("端末と SEND・RECEIVE でやり取りするか、他プログラムの XCTL・START の"
                            + "遷移先であるプログラムを CICS プログラムとみなし、"
                            + "RETURN（TRANSID の有無を問わない）の有無で検出します。"
                            + "LINK や CALL の呼び出し先は GOBACK で呼び出し元へ戻るため、"
                            + "ファイルやキューを扱う CICS コマンドだけでは対象になりません。"
                            + "STOP・GOBACK・EXIT PROGRAM のいずれも持たないプログラムは対象外です。")
                    .remedy("処理の終わりに EXEC CICS RETURN を置いてください。疑似会話を続けるなら"
                            + "TRANSID で次に起動するトランザクションを指定してください。")
                    .example("""
                            EXEC CICS SEND MAP('MAP01') MAPSET('MAPSET1') END-EXEC.
                            GOBACK.
                            """, """
                            EXEC CICS SEND MAP('MAP01') MAPSET('MAPSET1') END-EXEC.
                            EXEC CICS RETURN TRANSID('TR01') COMMAREA(WS-COMM) END-EXEC.
                            """)
                    .severity(Severity.MEDIUM)
                    .commands(Command.LINT)
                    .targets(AssetKind.COBOL)
                    .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        Set<String> transferTargets = transferTargets(context);
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            boolean participates = talksToTerminal(model)
                    || transferTargets.contains(CfgSupport.upper(model.programId()));
            if (!participates || hasReturn(model)) {
                continue;
            }
            Statement terminal = firstTerminal(model);
            if (terminal != null) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        model.programId() + " は EXEC CICS RETURN を持たずに終端します。"
                                + "疑似会話の制御が CICS に戻りません。",
                        new SourcePosition(model.sourceFile(), terminal.range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
        return findings;
    }

    static Set<String> transferTargets(AnalysisContext context) {
        Set<String> targets = new LinkedHashSet<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            for (EmbeddedBlock block : model.embeddedBlocks()) {
                if (block.kind() == EmbeddedBlockKind.CICS_XCTL
                        || block.kind() == EmbeddedBlockKind.CICS_START) {
                    String program = block.operands().get("PROGRAM");
                    if (program != null && !program.isBlank()) {
                        targets.add(CfgSupport.upper(program));
                    }
                }
            }
        }
        return targets;
    }

    /**
     * SEND, RECEIVE, CONVERSE and RETURN TRANSID: the commands of a program that owns the
     * terminal conversation. A subprogram that only reads files or queues under CICS is CALLed
     * or LINKed and ends in GOBACK.
     */
    static boolean talksToTerminal(CobolSemanticModel model) {
        return model.embeddedBlocks().stream().anyMatch(block -> switch (block.kind()) {
            case CICS_SEND_MAP, CICS_RECEIVE_MAP, CICS_RETURN_TRANSID -> true;
            case CICS_OTHER -> {
                String verb = CfgSupport.upper(block.text()).replaceAll("\\s+", " ")
                        .replaceFirst("^.*?EXEC CICS ", "").split("[ (]", 2)[0];
                yield verb.equals("SEND") || verb.equals("RECEIVE") || verb.equals("CONVERSE");
            }
            default -> false;
        });
    }

    private static boolean hasReturn(CobolSemanticModel model) {
        return model.embeddedBlocks().stream()
                .anyMatch(block -> block.kind() == EmbeddedBlockKind.CICS_RETURN_TRANSID
                        || block.kind() == EmbeddedBlockKind.CICS_RETURN);
    }

    private static Statement firstTerminal(CobolSemanticModel model) {
        // Prefer a top-level terminal statement of a paragraph (more likely unconditional).
        for (Procedure procedure : model.procedures()) {
            for (Statement statement : procedure.statements()) {
                if (isTerminal(statement)) {
                    return statement;
                }
            }
        }
        // If none at top level, look for a nested terminal statement inside IF/EVALUATE.
        Statement[] nested = {null};
        for (Procedure procedure : model.procedures()) {
            CfgSupport.walk(procedure.statements(), statement -> {
                if (nested[0] == null && isTerminal(statement)) {
                    nested[0] = statement;
                }
            });
            if (nested[0] != null) {
                break;
            }
        }
        return nested[0];
    }

    private static boolean isTerminal(Statement statement) {
        if (statement instanceof SimpleStatement simple) {
            String verb = CfgSupport.upper(simple.verb());
            return verb.equals("STOP") || verb.equals("GOBACK")
                    || (verb.equals("EXIT") && CfgSupport.upper(simple.text()).contains("PROGRAM"));
        }
        return false;
    }
}

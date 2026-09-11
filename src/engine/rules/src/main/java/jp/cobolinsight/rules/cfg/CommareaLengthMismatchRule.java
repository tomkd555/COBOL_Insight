package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.callgraph.CallGraph;
import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R036 A COMMAREA shorter than the DFHCOMMAREA that receives it. CICS copies only the bytes the
 * caller passes; the callee's LINKAGE record covers the rest with storage nobody set. The passed
 * length is the LENGTH operand when it is a number, the item's length when LENGTH names it with
 * LENGTH OF, and the COMMAREA item's own length when LENGTH is absent. The target is the PROGRAM
 * operand, or the program the transaction resolves to in the call graph. A length above the CICS
 * limit of 32763 bytes is reported on its own.
 */
public final class CommareaLengthMismatchRule implements Rule {

    /** The largest COMMAREA CICS accepts; a longer one fails the command with LENGERR. */
    private static final int MAX_LENGTH = 32763;
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern LENGTH_OF =
            Pattern.compile("(?i)^LENGTH\\s+OF\\s+([\\p{L}\\p{N}$#_-]+)$");
    private static final String TRANSACTION_PREFIX = "transaction:";
    private static final String PROGRAM_PREFIX = "program:";

    private static final RuleMeta META =
            RuleMeta.named("R036", "DFHCOMMAREA と渡される COMMAREA の長さ不一致", "CICS")
                    .summary("呼び出し先の DFHCOMMAREA より短い COMMAREA を渡す"
                            + "LINK・XCTL・RETURN TRANSID を検出します。")
                    .rationale("渡された長さを超える部分には何も転記されないため、"
                            + "呼び出し先は内容の定まらない領域を読みます。")
                    .detection("LINK・XCTL・RETURN TRANSID の COMMAREA について、渡す長さを "
                            + "LENGTH の数字定数、LENGTH OF が示す項目の長さ、"
                            + "または COMMAREA の項目の長さとします。"
                            + "遷移先の 01 DFHCOMMAREA の長さが渡す長さを上回るものを検出します。"
                            + "あわせて渡す長さが 32763 バイトを超えるものを検出します。"
                            + "解析対象にない遷移先と、長さの求まらない作用対象は対象外です。")
                    .remedy("呼び出し先の DFHCOMMAREA と同じ並びの項目を渡すか、"
                            + "呼び出し先で EIBCALEN の長さまでを使うようにしてください。")
                    .example("""
                            EXEC CICS XCTL PROGRAM('FLO050')
                                 COMMAREA(WS-XCTL-AREA) LENGTH(11) END-EXEC
                            """, """
                            EXEC CICS XCTL PROGRAM('FLO050')
                                 COMMAREA(WS-COMMAREA)
                                 LENGTH(LENGTH OF WS-COMMAREA) END-EXEC
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
        Map<String, CobolSemanticModel> byProgramId = new LinkedHashMap<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            byProgramId.putIfAbsent(CfgSupport.upper(model.programId()), model);
        }
        CallGraph graph = context.callGraph().orElse(null);
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            for (EmbeddedBlock block : model.embeddedBlocks()) {
                if (!transfers(block.kind()) || !block.operands().containsKey("COMMAREA")) {
                    continue;
                }
                evaluate(model, block, byProgramId, graph, findings);
            }
        }
        return findings;
    }

    private static void evaluate(CobolSemanticModel model, EmbeddedBlock block,
            Map<String, CobolSemanticModel> byProgramId, CallGraph graph, List<Finding> findings) {
        Integer passed = passedLength(model, block);
        if (passed == null) {
            return;
        }
        SourcePosition at = new SourcePosition(model.sourceFile(),
                CfgSupport.operandLine(block, "COMMAREA"), 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
        if (passed > MAX_LENGTH) {
            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                    "COMMAREA の " + passed + " バイトが上限を超えています。"
                            + "CICS が受け付ける長さは 32763 バイトまでです。", at));
            return;
        }
        String target = targetProgram(block, graph);
        CobolSemanticModel callee = target == null ? null
                : byProgramId.get(CfgSupport.upper(target));
        if (callee == null) {
            return;
        }
        Integer received = CfgSupport.itemNamed(callee, "DFHCOMMAREA")
                .flatMap(CfgSupport::byteLength).orElse(null);
        if (received == null || received <= passed) {
            return;
        }
        findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                CfgSupport.upper(target) + " の DFHCOMMAREA（" + received + " バイト）に "
                        + passed + " バイトしか渡していません。", at));
    }

    private static boolean transfers(EmbeddedBlockKind kind) {
        return kind == EmbeddedBlockKind.CICS_LINK || kind == EmbeddedBlockKind.CICS_XCTL
                || kind == EmbeddedBlockKind.CICS_RETURN_TRANSID;
    }

    /** The number of bytes the command passes, or null when neither the LENGTH operand nor the item resolves. */
    private static Integer passedLength(CobolSemanticModel model, EmbeddedBlock block) {
        String length = block.operands().get("LENGTH");
        if (length == null) {
            return itemLength(model, block.operands().get("COMMAREA"));
        }
        String text = length.trim();
        if (NUMBER.matcher(text).matches()) {
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Matcher lengthOf = LENGTH_OF.matcher(text);
        return lengthOf.matches() ? itemLength(model, lengthOf.group(1)) : null;
    }

    private static Integer itemLength(CobolSemanticModel model, String name) {
        return name == null ? null
                : CfgSupport.itemNamed(model, name).flatMap(CfgSupport::byteLength).orElse(null);
    }

    /** The program the transfer reaches: the PROGRAM operand, or what the transaction resolves to. */
    private static String targetProgram(EmbeddedBlock block, CallGraph graph) {
        String program = block.operands().get("PROGRAM");
        if (program != null && !program.isBlank()) {
            return program;
        }
        String transaction = block.operands().get("TRANSID");
        if (transaction == null || graph == null) {
            return null;
        }
        String from = TRANSACTION_PREFIX + transaction.trim();
        for (CallGraphEdge edge : graph.edges()) {
            if (edge.fromId().equalsIgnoreCase(from) && edge.toId().startsWith(PROGRAM_PREFIX)) {
                return edge.toId().substring(PROGRAM_PREFIX.length());
            }
        }
        return null;
    }
}

package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * R044 A RECEIVE MAP whose MAPFAIL is never checked. MAPFAIL is raised when the terminal sends no
 * modified field — the operator pressed ENTER on an untouched screen — and the receiving area is
 * then left as it was, so a program that tests for NORMAL and then goes on regardless processes
 * the previous input. A branch that lets only a NORMAL response through — a positive test of
 * DFHRESP(NORMAL), or a negated one with an ELSE — treats MAPFAIL as a failure and is not
 * reported. Only a RECEIVE MAP that receives RESP is examined; one without RESP at all is R021's
 * finding, and one under NOHANDLE or a HANDLE CONDITION naming MAPFAIL or ERROR has its handling
 * elsewhere.
 */
public final class MapfailUncheckedRule implements Rule {

    private static final Pattern NOHANDLE =
            Pattern.compile("(?i)(?<![\\p{L}\\p{N}-])NOHANDLE(?![\\p{L}\\p{N}-])");
    private static final Pattern MAPFAIL_CONDITION =
            Pattern.compile("(?i)DFHRESP\\s*\\(\\s*MAPFAIL\\s*\\)");
    private static final Pattern NORMAL_CONDITION =
            Pattern.compile("(?i)DFHRESP\\s*\\(\\s*NORMAL\\s*\\)");
    private static final Pattern NOT = CfgSupport.wordPattern("NOT");
    private static final Pattern HANDLED =
            Pattern.compile("(?i)(?<![\\p{L}\\p{N}-])(MAPFAIL|ERROR)\\s*\\(");

    private static final RuleMeta META =
            RuleMeta.named("R044", "MAPFAIL を検査しない RECEIVE MAP", "CICS")
                    .summary("応答コードを受け取りながら MAPFAIL と比較しない"
                            + "EXEC CICS RECEIVE MAP を検出します。")
                    .rationale("入力のない画面から受け取ると受け取り領域が前回のまま残り、"
                            + "プログラムが同じ入力をもう一度処理します。")
                    .detection("RESP を受け取る RECEIVE MAP のうち、"
                            + "次の CICS コマンドまでの前方経路のどの条件も"
                            + "DFHRESP(MAPFAIL) と比較しないものを検出します。"
                            + "DFHRESP(NORMAL) と等しいときだけ処理を続ける分岐"
                            + "（否定条件なら ELSE を持つもの）は、MAPFAIL を異常として"
                            + "扱っているとみなして対象外です。"
                            + "NOHANDLE を持つもの、その RECEIVE MAP より前の行に MAPFAIL か ERROR を扱う "
                            + "HANDLE CONDITION があるもの、"
                            + "RESP を受け取らないもの（R021 の対象）は対象外です。")
                    .remedy("応答コードを DFHRESP(MAPFAIL) と比べる分岐を置いて、"
                            + "入力がない場合の処理を分けてください。")
                    .example("""
                            EXEC CICS RECEIVE MAP('FLM01') MAPSET('FLM010')
                                 INTO(FLM01I) RESP(WS-RESP) END-EXEC
                            IF WS-RESP NOT = DFHRESP(NORMAL)
                                PERFORM 9000-ERROR
                            END-IF
                            """, """
                            EXEC CICS RECEIVE MAP('FLM01') MAPSET('FLM010')
                                 INTO(FLM01I) RESP(WS-RESP) END-EXEC
                            EVALUATE WS-RESP
                                WHEN DFHRESP(NORMAL)
                                    PERFORM 2000-SHORI
                                WHEN DFHRESP(MAPFAIL)
                                    PERFORM 8100-MAPFAIL
                                WHEN OTHER
                                    PERFORM 9000-ERROR
                            END-EVALUATE
                            """)
                    .severity(Severity.LOW)
                    .commands(Command.LINT)
                    .targets(AssetKind.COBOL)
                    .needs(Needs.CFG, Needs.SOURCE_TEXT)
                    .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        ControlFlowGraphs cfgs = context.artifact(ControlFlowGraphs.class).orElse(null);
        if (cfgs == null) {
            return List.of();
        }
        SourceTextIndex texts = context.artifact(SourceTextIndex.class).orElse(null);
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            String[] lines = texts == null ? null : texts.textOf(model.sourceFile())
                    .map(text -> text.split("\n", -1)).orElse(null);
            cfgs.of(model).ifPresent(cfg -> evaluate(model, cfg, lines, findings));
        }
        return findings;
    }

    private static void evaluate(CobolSemanticModel model, ControlFlowGraph cfg, String[] lines,
            List<Finding> findings) {
        Set<CfgNode> cicsNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<SourceRange, CfgNode> byRange = new HashMap<>();
        for (CfgNode node : cfg.nodes()) {
            node.statement().ifPresent(statement -> {
                if (statement instanceof SimpleStatement simple
                        && "EXEC CICS".equals(simple.verb())) {
                    cicsNodes.add(node);
                    byRange.put(simple.range(), node);
                }
            });
        }
        List<Integer> handledFrom = model.embeddedBlocks().stream()
                .filter(block -> block.kind() == EmbeddedBlockKind.CICS_HANDLE_CONDITION)
                .filter(block -> HANDLED.matcher(block.text()).find())
                .map(block -> block.range().start().line())
                .toList();
        for (EmbeddedBlock block : model.embeddedBlocks()) {
            if (block.kind() != EmbeddedBlockKind.CICS_RECEIVE_MAP) {
                continue;
            }
            String resp = block.operands().getOrDefault("RESP", block.operands().get("RESP2"));
            int line = block.range().start().line();
            if (resp == null || NOHANDLE.matcher(block.text()).find()
                    || handledFrom.stream().anyMatch(handle -> handle < line)) {
                continue;
            }
            CfgNode start = byRange.get(block.range());
            if (start == null || CfgSupport.forwardHasMatch(cfg, start, cicsNodes::contains,
                    node -> handlesMapfail(node, lines))) {
                continue;
            }
            String map = block.operands().getOrDefault("MAP", "RECEIVE MAP");
            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                    map + " の RECEIVE MAP は MAPFAIL を検査していません。"
                            + "入力のない画面でも前回の受け取り内容で処理を続けます。",
                    new SourcePosition(model.sourceFile(), block.range().end().line(), 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET)));
        }
    }

    /**
     * Whether the node is a branch that names DFHRESP(MAPFAIL) in its condition or in a WHEN
     * clause, or one that lets only a NORMAL response through: a positive test of
     * DFHRESP(NORMAL), or a negated one with an ELSE.
     */
    private static boolean handlesMapfail(CfgNode node, String[] lines) {
        return node.statement()
                .map(statement -> statement instanceof CompoundStatement compound
                        && handlesMapfail(compound, lines))
                .orElse(false);
    }

    private static boolean handlesMapfail(CompoundStatement compound, String[] lines) {
        String header = headerText(compound, lines);
        if (MAPFAIL_CONDITION.matcher(header).find()) {
            return true;
        }
        if (!NORMAL_CONDITION.matcher(header).find()) {
            return false;
        }
        return !NOT.matcher(header).find() || compound.blocks().size() > 1;
    }

    /**
     * The condition and branch labels the model carries for the statement, plus the source lines
     * of its header up to its first nested statement: the model's condition text names only the
     * data items of an IF condition, so DFHRESP(...) is read from the source.
     */
    private static String headerText(CompoundStatement compound, String[] lines) {
        StringBuilder text = new StringBuilder(CfgSupport.referenceText(compound));
        if (lines == null) {
            return text.toString();
        }
        int first = compound.range().start().line();
        int last = compound.range().end().line();
        List<Statement> body = compound.blocks().get(0).statements();
        if (!body.isEmpty()) {
            last = Math.max(first, body.get(0).range().start().line() - 1);
        }
        for (int line = first; line <= Math.min(last, lines.length); line++) {
            text.append(' ').append(lines[line - 1]);
        }
        return text.toString();
    }
}

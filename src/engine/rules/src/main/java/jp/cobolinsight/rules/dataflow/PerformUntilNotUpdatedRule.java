package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.dataflow.DataFlowFacts;
import jp.cobolinsight.core.dataflow.ProgramDataFlow;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.ControlKind;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R012 A PERFORM UNTIL whose termination condition is never updated. Detects, as a possible
 * infinite loop, a configuration where the variable used in the UNTIL condition of a paragraph
 * PERFORM, or in the continuation condition of an inline PERFORM, is not updated by any statement
 * in the loop body (the set of nodes that can flow back to the loop header on the CFG) and does not
 * appear in the body text either. An 88-level condition name is resolved to its parent item, and a
 * special register such as SQLCODE (updated by the runtime) and a VARYING control variable are
 * treated as already updated.
 */
public final class PerformUntilNotUpdatedRule implements Rule {

    private static final Pattern NAME_TOKEN =
            Pattern.compile("[\\p{L}\\p{N}$#_-]*\\p{L}[\\p{L}\\p{N}$#_-]*");
    private static final Set<String> RESERVED = Set.of(
            "PERFORM", "UNTIL", "WITH", "TEST", "BEFORE", "AFTER", "VARYING", "FROM", "BY",
            "AND", "OR", "NOT", "IS", "EQUAL", "EQUALS", "GREATER", "LESS", "THAN", "TO", "THRU",
            "THROUGH", "GO", "END-PERFORM", "TIMES",
            "ZERO", "ZEROS", "ZEROES", "SPACE", "SPACES", "HIGH-VALUE", "HIGH-VALUES",
            "LOW-VALUE", "LOW-VALUES", "QUOTE", "QUOTES", "NULL", "NULLS", "TRUE", "FALSE", "ALL");

    private static final RuleMeta META = RuleMeta
            .named("R012", "終了条件が更新されない PERFORM UNTIL", "制御フロー")
            .summary("終了条件に使うデータ項目がループ本体のどこでも更新されない"
                    + "PERFORM UNTIL 文を検出します。")
            .rationale("条件が変わらないためループから抜けられず、処理が止まります。")
            .detection("UNTIL 条件のデータ項目が、ループ本体のどの文からも更新されず"
                    + "本体の文中にも現れないものを検出します。条件名は親項目に解決し、"
                    + "SQLCODE などの特殊レジスタと VARYING で変化させる項目は更新済みとみなします。")
            .remedy("ループ本体で条件のデータ項目を更新してください。"
                    + "ファイルの終わりなど外部の事象で終わる場合は、"
                    + "その結果を条件のデータ項目に反映してください。")
            .example("""
                    PERFORM UNTIL WS-EOF = "Y"
                        READ CUST-FILE INTO WS-REC
                    END-PERFORM.
                    """, """
                    PERFORM UNTIL WS-EOF = "Y"
                        READ CUST-FILE INTO WS-REC
                            AT END MOVE "Y" TO WS-EOF
                        END-READ
                    END-PERFORM.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT, Command.REPORT)
            .targets(AssetKind.COBOL)
            .needs(Needs.SEMANTIC, Needs.CFG, Needs.DATAFLOW, Needs.SOURCE_TEXT)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        DataFlowFacts facts = context.artifact(DataFlowFacts.class).orElse(null);
        ControlFlowGraphs cfgs = context.artifact(ControlFlowGraphs.class).orElse(null);
        SourceTextIndex texts = context.artifact(SourceTextIndex.class).orElse(null);
        if (facts == null || cfgs == null || texts == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            ProgramDataFlow df = facts.of(model).orElse(null);
            ControlFlowGraph cfg = cfgs.of(model).orElse(null);
            if (df != null && cfg != null) {
                evaluate(model, cfg, df, new DataFlowSupport(model, texts),
                        texts.textOf(model.sourceFile()).orElse(""), findings);
            }
        }
        return findings;
    }

    private void evaluate(CobolSemanticModel model, ControlFlowGraph cfg, ProgramDataFlow df,
            DataFlowSupport support, String source, List<Finding> findings) {
        for (CfgNode node : cfg.nodes()) {
            Statement statement = node.statement().orElse(null);
            List<String> condVars = conditionVariables(statement);
            if (condVars.isEmpty()) {
                continue;
            }
            Set<CfgNode> body = loopBody(cfg, node);
            if (body.isEmpty()) {
                continue;
            }
            Set<String> updated = updatedInBody(df, body, support);
            Set<String> varyingVars = varyingControlVars(statement, source);
            boolean terminable = false;
            for (String var : condVars) {
                if (support.isSpecialRegister(var) || varyingVars.contains(var)
                        || isUpdated(var, updated, support)) {
                    terminable = true;
                    break;
                }
            }
            if (!terminable) {
                int line = statement.range().start().line();
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        String.join(", ", condVars)
                                + " が PERFORM UNTIL の本体で更新されていません。ループから抜けられません。",
                        new SourcePosition(model.sourceFile(), line, 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    private static boolean isUpdated(String var, Set<String> updated, DataFlowSupport support) {
        if (updated.contains(var)) {
            return true;
        }
        return support.conditionParent(var).map(updated::contains).orElse(false);
    }

    /** The variables appearing in the loop header node's continuation condition. Empty if it is not a loop. */
    private List<String> conditionVariables(Statement statement) {
        if (statement instanceof CompoundStatement compound && compound.kind() == ControlKind.LOOP) {
            return names(compound.conditionText());
        }
        if (statement instanceof SimpleStatement simple
                && "PERFORM".equals(simple.verb().toUpperCase(Locale.ROOT))) {
            String upper = simple.text().toUpperCase(Locale.ROOT);
            int until = upper.indexOf(" UNTIL ");
            if (until >= 0) {
                return names(simple.text().substring(until + " UNTIL ".length()));
            }
        }
        return List.of();
    }

    /** The body nodes that can flow back to the loop header (forward reach ∩ backward reach, excluding the header itself). */
    private static Set<CfgNode> loopBody(ControlFlowGraph cfg, CfgNode header) {
        Set<CfgNode> forward = reach(cfg, header, true);
        if (!forward.contains(header) && cfg.successors(header).stream().noneMatch(forward::contains)) {
            return Set.of();
        }
        Set<CfgNode> backward = reach(cfg, header, false);
        Set<CfgNode> body = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CfgNode n : forward) {
            if (n != header && backward.contains(n)) {
                body.add(n);
            }
        }
        return body;
    }

    private static Set<CfgNode> reach(ControlFlowGraph cfg, CfgNode start, boolean forward) {
        Set<CfgNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<CfgNode> queue = new ArrayDeque<>();
        for (CfgNode next : forward ? cfg.successors(start) : cfg.predecessors(start)) {
            if (visited.add(next)) {
                queue.addLast(next);
            }
        }
        while (!queue.isEmpty()) {
            CfgNode n = queue.removeFirst();
            for (CfgNode next : forward ? cfg.successors(n) : cfg.predecessors(n)) {
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return visited;
    }

    /** The names updated within the body (each statement's defsAt, plus every data name appearing in the statement text). */
    private static Set<String> updatedInBody(ProgramDataFlow df, Set<CfgNode> body,
            DataFlowSupport support) {
        Set<String> updated = new LinkedHashSet<>();
        for (CfgNode n : body) {
            updated.addAll(df.defsAt(n));
            n.statement().ifPresent(s -> updated.addAll(names(textOf(s))));
        }
        return updated;
    }

    /** The control variables of an inline/paragraph PERFORM VARYING (from the original source). Treated as updated if they match the continuation-condition variable. */
    private static Set<String> varyingControlVars(Statement statement, String source) {
        int start = statement.range().start().line();
        int end = statement.range().end().line();
        String[] lines = source.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = start - 1; i < end && i < lines.length && i >= 0; i++) {
            sb.append(lines[i]).append(' ');
        }
        Matcher m = Pattern.compile("(?i)\\bVARYING\\s+([\\p{L}\\p{N}$#_-]*\\p{L}[\\p{L}\\p{N}$#_-]*)")
                .matcher(sb.toString());
        Set<String> vars = new LinkedHashSet<>();
        while (m.find()) {
            vars.add(m.group(1).toUpperCase(Locale.ROOT));
        }
        return vars;
    }

    private static String textOf(Statement statement) {
        if (statement instanceof SimpleStatement simple) {
            return simple.text();
        }
        if (statement instanceof CompoundStatement compound) {
            return compound.conditionText();
        }
        return "";
    }

    private static List<String> names(String text) {
        String masked = maskLiterals(text);
        List<String> out = new ArrayList<>();
        Matcher m = NAME_TOKEN.matcher(masked);
        while (m.find()) {
            String tok = m.group().toUpperCase(Locale.ROOT);
            if (!RESERVED.contains(tok) && !out.contains(tok)) {
                out.add(tok);
            }
        }
        return out;
    }

    private static String maskLiterals(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                sb.append(' ');
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

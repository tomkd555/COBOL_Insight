package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * R025 An expression whose binary operator has identical operands on both sides. Detects places in
 * a condition expression such as IF/EVALUATE where a comparison or logical operator has the same
 * variable or the same constant on both sides, making the condition always true or always false,
 * and places in a COMPUTE right-hand side that become a meaningless computation such as
 * {@code A - A} (always 0) or {@code A / A} (always 1). Both sides are judged by lexical match of a
 * single operand (variable, constant, or literal).
 */
public final class IdenticalOperandsRule implements Rule {

    /** A control character used for tokens that match by string-literal content (does not collide with data names or operators). */
    private static final char LITERAL_MARK = '';
    /**
     * Operators for which we ask whether both sides of a condition expression are identical.
     * Because {@link #isOperand} also uses this set to exclude operator tokens, it includes the
     * arithmetic {@code -} and {@code /} in addition to the condition operators.
     */
    private static final Set<String> CONDITION_OPS =
            Set.of("=", ">", "<", ">=", "<=", "<>", "AND", "OR", "-", "/");
    /** Operators for which identical operands on both sides of a COMPUTE right-hand side make the result a constant. {@code +} and {@code *} are not necessarily errors even when identical. */
    private static final Set<String> ARITHMETIC_OPS = Set.of("-", "/");
    /** A COBOL word boundary. Differs from Java's {@code \b} in that it treats the hyphen as part of a word. */
    private static final String WORD_BOUNDARY_BEFORE = "(?<![\\p{L}\\p{N}$#_-])";
    private static final String WORD_BOUNDARY_AFTER = "(?![\\p{L}\\p{N}$#_-])";

    private static final RuleMeta META =
            RuleMeta.named("R025", "二項演算子の両辺が同一の式", "データフロー")
                    .summary("条件式の両辺が同じ、または COMPUTE 文の右辺が A - A・A / A の"
                            + "形になっている箇所を検出する。")
                    .rationale("条件が常に真か常に偽になり、演算の結果は定数になる。"
                            + "多くは、別の項目を指すつもりだった書き間違いである。")
                    .detection("比較・論理演算子の両辺が単一の作用対象（データ項目・定数）として"
                            + "字句一致するもの、および COMPUTE 文の右辺の A - A・A / A を検出する。")
                    .remedy("意図した項目名に直す。意図どおりなら定数に置き換える。")
                    .example("""
                            IF WS-TOTAL = WS-TOTAL
                                PERFORM SHORI
                            END-IF.
                            """, """
                            IF WS-TOTAL = WS-LIMIT
                                PERFORM SHORI
                            END-IF.
                            """)
                    .severity(Severity.ADVISORY)
                    .commands(Command.LINT, Command.REPORT)
                    .targets(AssetKind.COBOL)
                    .needs(Needs.SEMANTIC, Needs.CFG)
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
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            ControlFlowGraph cfg = cfgs.of(model).orElse(null);
            if (cfg != null) {
                evaluate(model, cfg, findings);
            }
        }
        return findings;
    }

    private void evaluate(CobolSemanticModel model, ControlFlowGraph cfg, List<Finding> findings) {
        for (CfgNode node : cfg.nodes()) {
            Statement statement = node.statement().orElse(null);
            if (statement instanceof CompoundStatement compound) {
                reportIfIdentical(model, statement, compound.conditionText(), CONDITION_OPS, findings);
            } else if (statement instanceof SimpleStatement simple
                    && "COMPUTE".equals(simple.verb().toUpperCase(Locale.ROOT))) {
                int eq = simple.text().indexOf('=');
                if (eq >= 0) {
                    reportIfIdentical(model, statement, simple.text().substring(eq + 1),
                            ARITHMETIC_OPS, findings);
                }
            }
        }
    }

    private void reportIfIdentical(CobolSemanticModel model, Statement statement, String expression,
            Set<String> operators, List<Finding> findings) {
        List<String> tokens = tokenize(expression);
        for (int i = 1; i + 1 < tokens.size(); i++) {
            if (!operators.contains(tokens.get(i))) {
                continue;
            }
            String left = tokens.get(i - 1);
            String right = tokens.get(i + 1);
            if (isOperand(left) && isOperand(right) && left.equalsIgnoreCase(right)) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "二項演算子の両辺が同一の作用対象である。常に真、常に偽、または無意味な演算になる。",
                        new SourcePosition(model.sourceFile(), statement.range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
                return;
            }
        }
    }

    /** Splits an expression into string literals (tokens that match by content), data names, numbers, and operators. */
    private static List<String> tokenize(String expression) {
        Map<String, Integer> literalIds = new LinkedHashMap<>();
        String masked = maskLiterals(expression, literalIds).toUpperCase(Locale.ROOT);
        String worded = normalizeWordedOperators(masked);
        String spaced = spaceSymbolicOperators(worded);
        List<String> tokens = new ArrayList<>();
        for (String tok : spaced.trim().split("\\s+")) {
            if (!tok.isEmpty()) {
                tokens.add(tok);
            }
        }
        return tokens;
    }

    private static boolean isOperand(String token) {
        if (CONDITION_OPS.contains(token) || token.equals("(") || token.equals(")")) {
            return false;
        }
        return token.matches(".*[\\p{L}\\p{N}].*");
    }

    private static String maskLiterals(String text, Map<String, Integer> literalIds) {
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                int close = text.indexOf(c, i + 1);
                if (close < 0) {
                    sb.append(' ');
                    break;
                }
                String content = text.substring(i + 1, close);
                int id = literalIds.computeIfAbsent(content, k -> literalIds.size());
                sb.append(' ').append(LITERAL_MARK).append(id).append(LITERAL_MARK).append(' ');
                i = close + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Replaces word-form operators with symbols. Because COBOL words contain hyphens, the boundary
     * used is not Java's {@code \b} but one that does not match inside a name such as WS-IS-FLAG.
     */
    private static String normalizeWordedOperators(String s) {
        String r = replaceWord(s, "IS", " ");
        // NOT = ends in a symbol, so no word-end boundary is imposed.
        r = r.replaceAll(WORD_BOUNDARY_BEFORE + "NOT\\s*=", " <> ");
        r = replaceWord(r, "NOT\\s+EQUAL(?:\\s+TO)?", " <> ");
        r = replaceWord(r, "GREATER\\s+THAN\\s+OR\\s+EQUAL(?:\\s+TO)?", " >= ");
        r = replaceWord(r, "LESS\\s+THAN\\s+OR\\s+EQUAL(?:\\s+TO)?", " <= ");
        r = replaceWord(r, "GREATER\\s+THAN", " > ");
        r = replaceWord(r, "LESS\\s+THAN", " < ");
        r = replaceWord(r, "EQUAL(?:\\s+TO)?", " = ");
        return r;
    }

    private static String replaceWord(String s, String wordRegex, String replacement) {
        return s.replaceAll(WORD_BOUNDARY_BEFORE + "(?:" + wordRegex + ")" + WORD_BOUNDARY_AFTER,
                replacement);
    }

    /** Surrounds symbolic operators (= &lt; &gt; &gt;= &lt;= &lt;&gt; / * +) with spaces. Excludes the hyphen since it is part of a name. */
    private static String spaceSymbolicOperators(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '=' || c == '<' || c == '>') {
                char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
                if ((c == '>' || c == '<') && next == '=') {
                    sb.append(' ').append(c).append('=').append(' ');
                    i += 2;
                } else if (c == '<' && next == '>') {
                    sb.append(" <> ");
                    i += 2;
                } else {
                    sb.append(' ').append(c).append(' ');
                    i++;
                }
            } else if (c == '/' || c == '*' || c == '+') {
                sb.append(' ').append(c).append(' ');
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}

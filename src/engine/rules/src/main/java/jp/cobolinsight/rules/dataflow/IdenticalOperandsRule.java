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
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.AnalysisPhase;
import jp.cobolinsight.core.spi.Rule;
import jp.cobolinsight.core.spi.RuleDoc;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * R025 二項演算子の両辺が同一の式。IF/EVALUATE などの条件式で比較・論理演算子の両辺に同一の変数または
 * 同一の定数が指定され常真・常偽になる箇所、および COMPUTE 右辺で {@code A - A}(常に 0)・{@code A / A}
 * (常に 1)のような無意味な演算になる箇所を検出する。両辺は単一のオペランド(変数・定数・リテラル)の
 * 字句一致で判定する。
 */
public final class IdenticalOperandsRule implements Rule {

    /** 文字列リテラルの内容一致トークンに使う制御文字(データ名・演算子と衝突しない)。 */
    private static final char LITERAL_MARK = '';
    /**
     * 条件式で両辺の同一性を問う演算子。{@link #isOperand} が演算子トークンを除く判定にも用いる
     * ため、条件演算子に加えて算術の - と / を含める。
     */
    private static final Set<String> CONDITION_OPS =
            Set.of("=", ">", "<", ">=", "<=", "<>", "AND", "OR", "-", "/");
    /** COMPUTE 右辺で両辺が同一なら結果が定数になる演算子。+ と * は同一でも誤りとは言えない。 */
    private static final Set<String> ARITHMETIC_OPS = Set.of("-", "/");
    /** COBOL の語境界。ハイフンを語の構成文字に含める点で Java の {@code \b} と異なる。 */
    private static final String WORD_BOUNDARY_BEFORE = "(?<![\\p{L}\\p{N}$#_-])";
    private static final String WORD_BOUNDARY_AFTER = "(?![\\p{L}\\p{N}$#_-])";

    @Override
    public String id() {
        return "R025";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("二項演算子の両辺が同一の式", "データフロー")
                .summary("条件式の両辺が同じ、または COMPUTE 右辺が A - A・A / A の"
                        + "形になっている箇所を検出します。")
                .rationale("条件が常に真か常に偽になり、演算は定数になります。"
                        + "多くは、別の項目を指すつもりだった書き間違いです。")
                .detection("比較・論理演算子の両辺が単一のオペランド(変数・定数・リテラル)として"
                        + "字句一致するもの、および COMPUTE 右辺の A - A・A / A を検出します。")
                .remedy("意図した項目名へ直します。意図どおりなら定数へ置き換えます。")
                .example("""
                        IF WS-TOTAL = WS-TOTAL
                            PERFORM SHORI
                        END-IF.
                        """, """
                        IF WS-TOTAL = WS-LIMIT
                            PERFORM SHORI
                        END-IF.
                        """)
                .build();
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ADVISORY;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.DATA_FLOW;
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
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                        "二項演算子の両辺が同一のオペランドである。常真・常偽、または無意味な演算になる。",
                        new SourcePosition(model.sourceFile(), statement.range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
                return;
            }
        }
    }

    /** 式を、文字列リテラル(内容一致で同一トークン)・データ名・数値・演算子へ分解する。 */
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
     * 語形式の演算子を記号へ置き換える。COBOL の語はハイフンを含むため、境界には Java の
     * {@code \b} ではなく WS-IS-FLAG のような名前の内側に一致しない境界を課す。
     */
    private static String normalizeWordedOperators(String s) {
        String r = replaceWord(s, "IS", " ");
        // NOT = は記号で終わるため、語の終わりの境界を課さない。
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

    /** 記号演算子(= &lt; &gt; &gt;= &lt;= &lt;&gt; / * +)を空白で囲む。ハイフンは名前の一部なので除く。 */
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

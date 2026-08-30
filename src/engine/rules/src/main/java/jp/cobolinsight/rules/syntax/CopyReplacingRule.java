package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R024 A COPY REPLACING substitution that never fires. Detects the case where a substitution
 * target string named in a COPY statement's REPLACING clause never appears (zero occurrences)
 * in the content of the copybook the replacement applies to (comment lines excluded), so the
 * substitution never actually happens. When it does not happen, the names of the imported
 * items do not become the intended names. The COPY statement's text and the target copybook's
 * text come from SourceTextIndex.
 */
public final class CopyReplacingRule implements Rule {

    private static final Pattern COPY_KEYWORD = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}-])COPY(?![\\p{L}\\p{N}-])");
    private static final Pattern COPY_STATEMENT = Pattern.compile(
            "(?i)^COPY\\s+([\\p{L}\\p{N}][\\p{L}\\p{N}-]*)(.*)$", Pattern.DOTALL);
    /**
     * The content of a pseudo-text does not contain the == delimiter. If it could, then in a
     * REPLACING clause that writes multiple pairs, this would swallow everything from the
     * first pair's replacement text through the second pair's substitution target as a
     * single piece of content.
     */
    private static final Pattern REPLACING_TARGET = Pattern.compile(
            "(?i)(?:(LEADING|TRAILING)\\s+)?"
                    + "(?:==((?:(?!==).)*)==|'([^']*)'|\"([^\"]*)\""
                    + "|([\\p{L}\\p{N}][\\p{L}\\p{N}-]*))"
                    + "\\s+BY\\s+");
    private static final String WORD_BOUNDARY_BEFORE = "(?<![\\p{L}\\p{N}-])";
    private static final String WORD_BOUNDARY_AFTER = "(?![\\p{L}\\p{N}-])";
    /**
     * The cap on the number of logical lines read while searching for the COPY statement's
     * terminating period. This is a safeguard so that, in source that lacks the period, the
     * entire remainder of the data division is not swallowed as a single statement; the
     * value is set above the practical line count of a REPLACING clause.
     */
    private static final int MAX_STATEMENT_LINES = 20;

    private static final RuleMeta META = RuleMeta.named("R024", "COPY REPLACINGによる置換漏れ", "データ定義")
            .summary("COPY 文の REPLACING で指定した置換対象が、取り込むコピー句に"
                    + "一度も現れない箇所を検出します。")
            .rationale("置換が起きないため、取り込んだ項目の名前が意図した名前にならず、"
                    + "接頭辞の付け替えを前提にした後続の参照が解決できません。")
            .detection("REPLACING の置換対象文字列を、対象コピー句の内容(注記行を除く)と"
                    + "突き合わせ、出現が 0 件のものを検出します。")
            .remedy("置換対象の綴りをコピー句の記述と揃えます。不要になった REPLACING は削ります。")
            .example("""
                    COPY CUSTREC REPLACING ==:PFX:== BY ==CUST==.
                    """, """
                    COPY CUSTREC REPLACING ==:PRE:== BY ==CUST==.
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT, Command.REPORT)
            .targets(AssetKind.COBOL, AssetKind.COPYBOOK)
            .needs(Needs.SEMANTIC, Needs.SOURCE_TEXT)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        SourceTextIndex index = context.artifact(SourceTextIndex.class).orElse(null);
        if (index == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            String text = index.textOf(model.sourceFile()).orElse(null);
            if (text == null) {
                continue;
            }
            scanProgram(model.sourceFile(), text, index, findings);
        }
        return findings;
    }

    private static void scanProgram(String file, String text, SourceTextIndex index,
            List<Finding> findings) {
        List<CobolTexts.LogicalLine> lines = CobolTexts.logicalLines(text);
        for (int i = 0; i < lines.size(); i++) {
            String lineText = lines.get(i).text();
            Matcher keyword = COPY_KEYWORD.matcher(CobolTexts.stripLiterals(lineText));
            if (!keyword.find()) {
                continue;
            }
            StringBuilder statement = new StringBuilder(lineText.substring(keyword.start()));
            int consumed = 0;
            while (!endsWithPeriod(statement) && consumed < MAX_STATEMENT_LINES
                    && i + consumed + 1 < lines.size()) {
                consumed++;
                statement.append(' ').append(lines.get(i + consumed).text());
            }
            check(file, lines.get(i).lineNumber(), keyword.start() + 1, statement.toString(),
                    index, findings);
            i += consumed;
        }
    }

    private static boolean endsWithPeriod(CharSequence statement) {
        for (int i = statement.length() - 1; i >= 0; i--) {
            char c = statement.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c == '.';
            }
        }
        return false;
    }

    private static void check(String file, int line, int column, String statementText,
            SourceTextIndex index, List<Finding> findings) {
        String joined = statementText.replaceAll("\\s+", " ").trim();
        Matcher statement = COPY_STATEMENT.matcher(joined);
        if (!statement.matches()) {
            return;
        }
        String copybookName = statement.group(1);
        String rest = statement.group(2);
        if (!rest.toUpperCase(java.util.Locale.ROOT).contains("REPLACING")) {
            return;
        }
        String copybookText = index.textOfBaseName(copybookName).orElse(null);
        if (copybookText == null) {
            return;
        }
        String content = CobolTexts.upper(bodyContent(copybookText));
        Matcher target = REPLACING_TARGET.matcher(rest);
        while (target.find()) {
            String mode = target.group(1);
            String value = firstNonNull(target.group(2), target.group(3), target.group(4),
                    target.group(5));
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!occursIn(content, CobolTexts.upper(value.trim()), mode)) {
                findings.add(Finding.of("R024", Severity.MEDIUM.toLevel(),
                        "COPY文のREPLACING句で指定した置換対象 " + value.trim()
                                + " が、コピー句 " + copybookName + " の内容に一件も出現しない。",
                        new SourcePosition(file, line, column,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    /**
     * Judges whether the substitution target appears as a word in the copybook body text.
     * This requires the absence of COBOL word-forming characters on both sides, except that
     * LEADING matches at the start of a word (no trailing boundary) and TRAILING matches at
     * the end of a word (no leading boundary).
     */
    private static boolean occursIn(String content, String upperValue, String mode) {
        String quoted = Pattern.quote(upperValue);
        String regex;
        if ("LEADING".equalsIgnoreCase(mode)) {
            regex = WORD_BOUNDARY_BEFORE + quoted;
        } else if ("TRAILING".equalsIgnoreCase(mode)) {
            regex = quoted + WORD_BOUNDARY_AFTER;
        } else {
            regex = WORD_BOUNDARY_BEFORE + quoted + WORD_BOUNDARY_AFTER;
        }
        return Pattern.compile(regex).matcher(content).find();
    }

    private static String bodyContent(String copybookText) {
        StringBuilder out = new StringBuilder();
        for (CobolTexts.LogicalLine line : CobolTexts.logicalLines(copybookText)) {
            out.append(line.text()).append('\n');
        }
        return out.toString();
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}

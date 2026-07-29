package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R024 COPY REPLACINGによる置換漏れ。COPY文のREPLACING句で指定した置換対象の文字列が、
 * 置換の適用対象であるコピー句の内容(コメント行を除く)に一件も出現せず、置換が一度も
 * 行われない場合を検出する。置換が行われないと、取り込んだ項目の名前が意図した名前にならない。
 * COPY文と対象コピー句のテキストは SourceTextIndex から得る。
 */
public final class CopyReplacingRule implements Rule {

    private static final Pattern COPY_KEYWORD = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}-])COPY(?![\\p{L}\\p{N}-])");
    private static final Pattern COPY_STATEMENT = Pattern.compile(
            "(?i)^COPY\\s+([\\p{L}\\p{N}][\\p{L}\\p{N}-]*)(.*)$", Pattern.DOTALL);
    /**
     * 疑似テキストの中身は区切りの == を含まない。含み得るとすると、対を複数書いたREPLACING句で
     * 1対目の置換後テキストから2対目の置換対象までを1つの中身として取り込んでしまう。
     */
    private static final Pattern REPLACING_TARGET = Pattern.compile(
            "(?i)(?:(LEADING|TRAILING)\\s+)?"
                    + "(?:==((?:(?!==).)*)==|'([^']*)'|\"([^\"]*)\""
                    + "|([\\p{L}\\p{N}][\\p{L}\\p{N}-]*))"
                    + "\\s+BY\\s+");
    private static final String WORD_BOUNDARY_BEFORE = "(?<![\\p{L}\\p{N}-])";
    private static final String WORD_BOUNDARY_AFTER = "(?![\\p{L}\\p{N}-])";
    /**
     * COPY文の終止ピリオドを探して読み進める論理行の上限。ピリオドを欠くソースで後続のデータ部
     * 全体を1文として取り込まないための歯止めであり、REPLACING句の実務的な行数を上回る値を採る。
     */
    private static final int MAX_STATEMENT_LINES = 20;

    @Override
    public String id() {
        return "R024";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.MEDIUM;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.SYNTAX;
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
     * 置換対象がコピー句本文に語として出現するかを判定する。前後にCOBOL語構成文字が無いことを
     * 課すが、LEADINGは語頭一致(後方境界なし)、TRAILINGは語尾一致(前方境界なし)で照合する。
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

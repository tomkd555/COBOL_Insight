package jp.cobolinsight.rules.custom;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code match.kind = "line"} custom rule: a regular expression applied to each line of decoded
 * source.
 *
 * <p>At most one finding per line. Reporting the second and later matches on a line would put the
 * count out of step with the line-oriented check the author wrote.
 */
final class LineRule implements Rule {

    /** Writing this sequence in the message substitutes the matched text. */
    private static final String MATCH_PLACEHOLDER = "${match}";

    /** Column where the program area begins in fixed format (column 8, one-based). */
    private static final int PROGRAM_AREA_START = 7;

    /** Column where the program area ends in fixed format (column 72, one-based). */
    private static final int PROGRAM_AREA_END = 72;

    private final RuleMeta meta;
    private final Pattern pattern;
    private final Pattern excludePattern;
    private final String message;
    private final boolean wholeLine;

    LineRule(RuleMeta meta, Pattern pattern, Pattern excludePattern, String message,
            boolean wholeLine) {
        this.meta = meta;
        this.pattern = pattern;
        this.excludePattern = excludePattern;
        this.message = message;
        this.wholeLine = wholeLine;
    }

    @Override
    public RuleMeta meta() {
        return meta;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext ctx) {
        SourceTextIndex texts = ctx.artifact(SourceTextIndex.class).orElse(null);
        if (texts == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, String> entry : texts.entries().entrySet()) {
            AssetKind kind = AssetKind.ofFileName(entry.getKey());
            if (kind == null || !meta.targets().contains(kind)) {
                continue;
            }
            scan(entry.getKey(), kind, entry.getValue(), findings);
        }
        return findings;
    }

    private void scan(String path, AssetKind kind, String text, List<Finding> findings) {
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String scannable = scannableTextOf(lines[index].replace("\r", ""), kind);
            if (scannable == null) {
                continue;
            }
            Matcher matcher = pattern.matcher(scannable);
            if (!matcher.find()) {
                continue;
            }
            if (excludePattern != null && excludePattern.matcher(scannable).find()) {
                continue;
            }
            findings.add(Finding.of(meta.id(), meta.defaultSeverity().toLevel(),
                    message.replace(MATCH_PLACEHOLDER, matcher.group()),
                    new SourcePosition(path, index + 1, matcher.start() + 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET)));
        }
    }

    /**
     * The scannable span of one line, or null for a comment line. COBOL and copybooks have their
     * sequence number and indicator areas replaced by spaces and their identification area cut off,
     * so the reported column still matches the column in the original source. BMS and JCL have no
     * fixed-format column layout, so the whole line is returned.
     */
    private String scannableTextOf(String raw, AssetKind kind) {
        if (wholeLine || (kind != AssetKind.COBOL && kind != AssetKind.COPYBOOK)) {
            return raw;
        }
        if (raw.length() > 6) {
            char indicator = raw.charAt(6);
            if (indicator == '*' || indicator == '/') {
                return null;
            }
        }
        String line = raw.length() > PROGRAM_AREA_END ? raw.substring(0, PROGRAM_AREA_END) : raw;
        if (line.length() <= PROGRAM_AREA_START) {
            return line;
        }
        return " ".repeat(PROGRAM_AREA_START) + line.substring(PROGRAM_AREA_START);
    }
}

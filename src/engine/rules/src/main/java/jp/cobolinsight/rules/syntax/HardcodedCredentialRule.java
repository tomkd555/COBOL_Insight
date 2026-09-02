package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R026 Hardcoded password or credential. Detects places where a non-blank string literal
 * is written directly on the same line as an identifier that represents a password, an
 * API key, or a secret token. Because both the VALUE clause and MOVE statement forms are
 * targeted, this scans the original source text via SourceTextIndex.
 */
public final class HardcodedCredentialRule implements Rule {

    // A hyphen counts as a boundary, being a segment separator inside a COBOL identifier
    // (WS-PASSWORD and WS-DB-PASSWORD are detected; a mid-word partial match like TOKENIZE is not).
    private static final Pattern CREDENTIAL_KEYWORD = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:PASSWORD|PASSWD|PSWD|PWD|API-?KEY|SECRET|TOKEN)"
                    + "(?![A-Za-z0-9])");
    private static final Pattern STRING_LITERAL = Pattern.compile("'([^']*)'|\"([^\"]*)\"");

    private static final RuleMeta META = RuleMeta.named("R026", "原始プログラムに直接書かれた資格情報", "セキュリティ")
            .summary("パスワード・API キー・トークンを表す項目と同じ行に、"
                    + "文字定数が直接書かれている箇所を検出します。")
            .rationale("原始プログラムを読める者が資格情報をそのまま得られます。"
                    + "資格情報の変更のたびに再コンパイルと再配布が要る点でも運用を縛ります。")
            .detection("原始プログラムの各行を走査し、資格情報を表す識別子と空白以外の"
                    + "文字定数が同じ行にある VALUE 句・MOVE 文を検出します。"
                    + "注記行と 73桁以降の識別領域は対象外です。")
            .remedy("資格情報を外部の資格情報管理へ移し、実行時に受け取ってください。")
            .example("""
                    01  WS-DB-PASSWORD  PIC X(16) VALUE "P@ssw0rd123".
                    """, """
                    01  WS-DB-PASSWORD  PIC X(16).
                        CALL "GETCRED" USING WS-DB-PASSWORD.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT, Command.REPORT)
            .targets(AssetKind.COBOL, AssetKind.COPYBOOK)
            .needs(Needs.SOURCE_TEXT)
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
        for (Map.Entry<String, String> entry : index.entries().entrySet()) {
            scan(entry.getKey(), entry.getValue(), findings);
        }
        return findings;
    }

    private static void scan(String file, String text, List<Finding> findings) {
        for (CobolTexts.LogicalLine line : CobolTexts.logicalLines(text)) {
            String stripped = CobolTexts.stripLiterals(line.text());
            Matcher keyword = CREDENTIAL_KEYWORD.matcher(stripped);
            if (!keyword.find()) {
                continue;
            }
            String identifier = identifierAround(stripped, keyword.start(), keyword.end());
            Matcher literal = STRING_LITERAL.matcher(line.text());
            while (literal.find()) {
                String value = literal.group(1) != null ? literal.group(1) : literal.group(2);
                if (value == null || value.isBlank()) {
                    continue;
                }
                findings.add(Finding.of("R026", Severity.HIGH.toLevel(),
                        identifier + " に文字定数で資格情報が書かれています。",
                        new SourcePosition(file, line.lineNumber(), literal.start() + 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    /** The whole COBOL identifier around a keyword match: WS-DB-PASSWORD for the match PASSWORD. */
    private static String identifierAround(String text, int start, int end) {
        while (start > 0 && isIdentifierChar(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && isIdentifierChar(text.charAt(end))) {
            end++;
        }
        return text.substring(start, end);
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }
}

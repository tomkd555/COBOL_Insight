package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.AnalysisPhase;
import jp.cobolinsight.core.spi.Rule;
import jp.cobolinsight.core.spi.RuleDoc;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R026 ハードコードされたパスワード・認証情報。パスワード・APIキー・シークレットトークンを
 * 表す識別子と同じ行に、空白以外の文字列リテラルが直接記述されている箇所を検出する。
 * VALUE句・MOVE文のいずれの形式も対象とするため、SourceTextIndex の原ソーステキストを走査する。
 */
public final class HardcodedCredentialRule implements Rule {

    // ハイフンは COBOL 識別子内のセグメント区切りとして境界に数える
    // (WS-PASSWORD・WS-DB-PASSWORD は検出対象、TOKENIZE のような語中の部分一致は対象外)。
    private static final Pattern CREDENTIAL_KEYWORD = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:PASSWORD|PASSWD|PSWD|PWD|API-?KEY|SECRET|TOKEN)"
                    + "(?![A-Za-z0-9])");
    private static final Pattern STRING_LITERAL = Pattern.compile("'([^']*)'|\"([^\"]*)\"");

    @Override
    public String id() {
        return "R026";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("ハードコードされたパスワード・認証情報", "セキュリティ")
                .summary("パスワード・APIキー・トークンを表す項目と同じ行に、"
                        + "文字列リテラルが直接書かれている箇所を検出します。")
                .rationale("ソースを読める者が資格情報をそのまま得られます。"
                        + "資格情報の変更のたびに再コンパイルと再配布が要る点でも運用を縛ります。")
                .detection("原ソーステキストを走査し、資格情報を表す識別子と空白以外の"
                        + "文字列リテラルが同じ行にある VALUE 句・MOVE 文を検出します。")
                .remedy("資格情報を外部の資格情報管理へ移し、実行時に受け取る形にします。")
                .example("""
                        01  WS-DB-PASSWORD  PIC X(16) VALUE "P@ssw0rd123".
                        """, """
                        01  WS-DB-PASSWORD  PIC X(16).
                            CALL "GETCRED" USING WS-DB-PASSWORD.
                        """)
                .build();
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.HIGH;
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
        for (Map.Entry<String, String> entry : index.entries().entrySet()) {
            scan(entry.getKey(), entry.getValue(), findings);
        }
        return findings;
    }

    private static void scan(String file, String text, List<Finding> findings) {
        for (CobolTexts.LogicalLine line : CobolTexts.logicalLines(text)) {
            if (!CREDENTIAL_KEYWORD.matcher(CobolTexts.stripLiterals(line.text())).find()) {
                continue;
            }
            Matcher literal = STRING_LITERAL.matcher(line.text());
            while (literal.find()) {
                String value = literal.group(1) != null ? literal.group(1) : literal.group(2);
                if (value == null || value.isBlank()) {
                    continue;
                }
                findings.add(Finding.of("R026", Severity.HIGH.toLevel(),
                        "パスワード・APIキー・シークレットトークンのいずれかの認証情報が、"
                                + "文字列リテラルとして直接記述されている。",
                        new SourcePosition(file, line.lineNumber(), literal.start() + 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }
}

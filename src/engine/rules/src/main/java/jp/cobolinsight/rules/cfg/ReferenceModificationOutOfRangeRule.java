package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R042 A reference modification {@code item(start:length)} whose bounds, given as numeric
 * literals, run past the item's storage byte length (from PICTURE, USAGE, OCCURS and descendant
 * group items). Reading or writing past the item's length touches storage nobody declared there.
 * A reference modification whose start or length is a data name or an expression is not judged,
 * since its value is not known at this point.
 */
public final class ReferenceModificationOutOfRangeRule implements Rule {

    private static final Pattern REF_MOD = Pattern.compile(
            "([\\p{L}\\p{N}$#_-]*\\p{L}[\\p{L}\\p{N}$#_-]*)\\s*\\(\\s*(\\d+)\\s*:\\s*(\\d+)\\s*\\)");

    private static final RuleMeta META =
            RuleMeta.named("R042", "項目長を超える部分参照", "データ定義")
                    .summary("項目の長さを超える範囲を指す部分参照を検出します。")
                    .rationale("定義されていない領域を読み書きし、"
                            + "隣接する項目を壊すか値の定まらない領域を読みます。")
                    .detection("部分参照 item(start:length) の start と length がともに数字定数のとき、"
                            + "PICTURE・USAGE・OCCURS から求めた項目の長さと比較します。"
                            + "start + length - 1 がこれを超えるものと、start 自体がこれを超えるものを"
                            + "検出します。start・length がデータ項目や式で与えられる部分参照は対象外です。")
                    .remedy("部分参照の範囲を項目の長さ以内に収めてください。")
                    .example("""
                            01  WS-NAME  PIC X(20).
                                MOVE WS-NAME(20:5) TO WS-OUT.
                            """, """
                            01  WS-NAME  PIC X(20).
                                MOVE WS-NAME(16:5) TO WS-OUT.
                            """)
                    .severity(Severity.HIGH)
                    .commands(Command.LINT)
                    .targets(AssetKind.COBOL)
                    .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            Set<String> seen = new LinkedHashSet<>();
            for (Procedure procedure : model.procedures()) {
                CfgSupport.walk(procedure.statements(),
                        statement -> evaluate(model, statement, seen, findings));
            }
        }
        return findings;
    }

    private static void evaluate(CobolSemanticModel model, Statement statement, Set<String> seen,
            List<Finding> findings) {
        String text = maskLiterals(CfgSupport.referenceText(statement));
        int line = statement.range().start().line();
        Matcher m = REF_MOD.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            int start = Integer.parseInt(m.group(2));
            int length = Integer.parseInt(m.group(3));
            DataItem item = CfgSupport.itemNamed(model, name).orElse(null);
            if (item == null) {
                continue;
            }
            Integer byteLength = CfgSupport.byteLength(item).orElse(null);
            if (byteLength == null || (start <= byteLength && start + length - 1 <= byteLength)) {
                continue;
            }
            String key = line + ":" + name + ":" + start + ":" + length;
            if (!seen.add(key)) {
                continue;
            }
            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                    name + "(" + start + ":" + length + ") が項目の長さ " + byteLength
                            + "バイトを超えています。定義されていない領域を参照します。",
                    new SourcePosition(model.sourceFile(), line, 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET)));
        }
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

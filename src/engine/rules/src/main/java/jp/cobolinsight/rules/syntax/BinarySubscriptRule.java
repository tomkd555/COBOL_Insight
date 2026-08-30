package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R006 Non-binary item used as a subscript. Among subscripted references to a table (an
 * item with an OCCURS clause, or an item nested inside one), detects places where the
 * data item used as the subscript has a USAGE clause other than BINARY (COMP). A DISPLAY-
 * format subscript is converted to binary on every reference, which slows down table
 * access. Scans the "name(subscript)" form in the statement text; an INDEXED BY index name
 * or a literal subscript is excluded.
 */
public final class BinarySubscriptRule implements Rule {

    // Allow one level of nested parentheses so that the outer table reference is still
    // captured when the subscript itself is a subscripted (nested) reference.
    private static final Pattern SUBSCRIPTED = Pattern.compile(
            "([\\p{L}\\p{N}][\\p{L}\\p{N}-]*)\\s*\\(((?:[^()]|\\([^()]*\\))*)\\)");
    // The mapper preserves the USAGE clause spelling as-is, so include the full spelling of
    // COMPUTATIONAL as well.
    private static final Set<String> BINARY_USAGES = Set.of("COMP", "COMP-4", "COMP-5",
            "COMPUTATIONAL", "COMPUTATIONAL-4", "COMPUTATIONAL-5", "BINARY", "INDEX");

    private static final RuleMeta META = RuleMeta.named("R006", "添字への二進項目未使用", "添字・指標")
            .summary("表の添字に USAGE BINARY 以外のデータ項目を使っている参照を検出します。")
            .rationale("DISPLAY 形式の添字は参照のたびに二進数へ変換されるため、"
                    + "表を繰り返し参照する処理の実行時間が伸びます。")
            .detection("文テキスト上の「名前(添字)」形式のうち、添字がデータ項目で、"
                    + "その USAGE が BINARY(COMP)でないものを検出します。"
                    + "INDEXED BY の指標名とリテラルの添字は対象外とします。")
            .remedy("添字に使う項目を USAGE BINARY(COMP)で宣言するか、INDEXED BY の指標を使います。")
            .example("""
                    01  WS-IDX  PIC 9(4).
                        MOVE WS-TBL(WS-IDX) TO WS-OUT.
                    """, """
                    01  WS-IDX  PIC 9(4) USAGE BINARY.
                        MOVE WS-TBL(WS-IDX) TO WS-OUT.
                    """)
            .severity(Severity.LOW)
            .commands(Command.LINT, Command.REPORT)
            .targets(AssetKind.COBOL)
            .needs(Needs.SEMANTIC)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            Map<String, DataItem> itemsByName = new HashMap<>();
            Set<String> tableNames = new HashSet<>();
            collect(model.dataItems(), false, itemsByName, tableNames);
            Set<String> reported = new HashSet<>();
            Statements.walk(model, statement -> {
                SourceRange range;
                List<String> texts;
                if (statement instanceof SimpleStatement simple) {
                    range = simple.range();
                    texts = List.of(simple.text());
                } else if (statement instanceof CompoundStatement compound) {
                    range = compound.range();
                    texts = Statements.ownTexts(compound);
                } else {
                    return;
                }
                for (String text : texts) {
                    scan(text, range, itemsByName, tableNames, reported, findings);
                }
            });
        }
        return findings;
    }

    /** Collects an item-name-to-definition index, plus the names that can be subscripted (the item itself or an ancestor has OCCURS). */
    private static void collect(List<DataItem> items, boolean ancestorOccurs,
            Map<String, DataItem> itemsByName, Set<String> tableNames) {
        for (DataItem item : items) {
            String name = CobolTexts.upper(item.name());
            itemsByName.putIfAbsent(name, item);
            boolean inTable = ancestorOccurs || item.occurs().isPresent();
            if (inTable) {
                tableNames.add(name);
            }
            collect(item.children(), inTable, itemsByName, tableNames);
        }
    }

    private static void scan(String text, SourceRange range, Map<String, DataItem> itemsByName,
            Set<String> tableNames, Set<String> reported, List<Finding> findings) {
        Matcher matcher = SUBSCRIPTED.matcher(CobolTexts.stripLiterals(text));
        while (matcher.find()) {
            String tableName = CobolTexts.upper(matcher.group(1));
            if (!tableNames.contains(tableName)) {
                continue;
            }
            Matcher args = CobolTexts.NAME.matcher(matcher.group(2));
            while (args.find()) {
                String arg = args.group();
                DataItem subscript = itemsByName.get(CobolTexts.upper(arg));
                if (subscript == null) {
                    continue;
                }
                String usage = subscript.usage().map(CobolTexts::upper).orElse("DISPLAY");
                if (BINARY_USAGES.contains(usage)) {
                    continue;
                }
                String key = range.start().line() + ":" + tableName + ":"
                        + CobolTexts.upper(arg);
                if (reported.add(key)) {
                    findings.add(Finding.of("R006", Severity.LOW.toLevel(),
                            "表 " + matcher.group(1) + " の添字に使うデータ項目 " + arg
                                    + " のUSAGE句がBINARY(COMP)以外である(USAGE: " + usage + ")。",
                            range.start()));
                }
            }
        }
    }
}

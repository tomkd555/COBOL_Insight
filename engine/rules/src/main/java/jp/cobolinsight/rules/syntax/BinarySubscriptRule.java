package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.CompoundStatement;
import jp.cobolinsight.engineapi.semantic.DataItem;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.source.SourceRange;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R006 添字への二進項目未使用。表(OCCURS句を持つ項目、またはその内側の項目)への添字付き参照の
 * うち、添字に使うデータ項目のUSAGE句がBINARY(COMP)以外である箇所を検出する。DISPLAY形式の
 * 添字は参照のたびに二進数への変換を伴い、表参照の性能を落とす。文テキスト上の
 * 「名前(添字)」形式を走査し、INDEXED BY の指標名やリテラル添字は対象外とする。
 */
public final class BinarySubscriptRule implements Rule {

    // 添字自体が添字付き参照(入れ子)の場合も外側の表参照を捕捉するため、括弧1段の入れ子を許す。
    private static final Pattern SUBSCRIPTED = Pattern.compile(
            "([\\p{L}\\p{N}][\\p{L}\\p{N}-]*)\\s*\\(((?:[^()]|\\([^()]*\\))*)\\)");
    // マッパーはUSAGE句の綴りをそのまま保持するため、COMPUTATIONALの完全綴りも含める。
    private static final Set<String> BINARY_USAGES = Set.of("COMP", "COMP-4", "COMP-5",
            "COMPUTATIONAL", "COMPUTATIONAL-4", "COMPUTATIONAL-5", "BINARY", "INDEX");

    @Override
    public String id() {
        return "R006";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.LOW;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.SYNTAX;
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

    /** 項目名→定義の索引と、添字付き参照の対象になりうる名前(自身または祖先がOCCURSを持つ)を集める。 */
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

package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.picture.PictureType;
import jp.cobolinsight.core.picture.Usage;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R049 An arithmetic statement's operand, or a numeric relation's operand, that is a USAGE
 * DISPLAY numeric item declared under an FD record or in LINKAGE, with no {@code NUMERIC} test
 * anywhere in the program. This is the arithmetic/relation-operand half of R034's plan row,
 * split into its own rule so its default state can be decided from measurement (see R034 for the
 * MOVE case).
 *
 * <p>ponytail: relation operands are matched on the symbolic operators ({@code >}, {@code <},
 * {@code =}, {@code >=}, {@code <=}, {@code <>}) only; none of the three fixture folders uses the
 * word forms (GREATER THAN, LESS THAN, EQUAL TO), so scanning them is added if a fixture needs it.
 */
public final class UntestedNumericInputArithmeticRule implements Rule {

    private static final Set<String> ARITHMETIC_VERBS =
            Set.of("ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "COMPUTE");
    private static final Set<String> RESERVED = Set.of("ADD", "SUBTRACT", "MULTIPLY", "DIVIDE",
            "COMPUTE", "TO", "FROM", "GIVING", "BY", "ROUNDED", "ON", "SIZE", "ERROR", "NOT",
            "REMAINDER", "CORRESPONDING", "CORR", "END-ADD", "END-SUBTRACT", "END-MULTIPLY",
            "END-DIVIDE", "END-COMPUTE");
    private static final Pattern RELATION_OP = Pattern.compile("<>|>=|<=|[><=]");

    private static final RuleMeta META = RuleMeta
            .named("R049", "字類検査を経ない入力数字項目の演算", "データ移動")
            .summary("ファイル節または連絡節の USAGE DISPLAY 数字項目のうち、"
                    + "NUMERIC で検査せずに算術文または数値の比較条件に使っているものを検出します。")
            .rationale("レコードまたは呼び出し元から受け取った値がそのまま演算や比較に使われ、"
                    + "数字として扱えない値があれば実行時に異常終了します。")
            .detection("対象は、ADD・SUBTRACT・MULTIPLY・DIVIDE・COMPUTE の作用対象と"
                    + "数値の比較条件の作用対象のうち、ファイル節または連絡節に "
                    + "USAGE DISPLAY で宣言された基本項目です。そのうち、原始プログラムのどこにも"
                    + "その項目を NUMERIC で検査する条件がないものを検出します。"
                    + "定数、集団項目、COMP・COMP-3 の項目、作業場所節・局所記憶節の項目、"
                    + "検査済みの項目は対象外です。")
            .remedy("演算または比較の前に対象の項目を NUMERIC で検査し、数字でない値を除いてください。")
            .example("""
                    FD  KEIYAKU.
                    01  IN-レコード.
                        05  IN-入金額   PIC 9(07).
                        ADD IN-入金額 TO WS-合計金額.
                    """, """
                    FD  KEIYAKU.
                    01  IN-レコード.
                        05  IN-入金額   PIC 9(07).
                        IF IN-入金額 IS NUMERIC
                            ADD IN-入金額 TO WS-合計金額
                        END-IF.
                    """)
            .severity(Severity.LOW)
            .defaultEnabled(true)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .needs(Needs.SOURCE_TEXT)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        SourceTextIndex texts = context.artifact(SourceTextIndex.class).orElse(null);
        if (texts == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            String source = texts.textOf(model.sourceFile()).orElse(null);
            if (source == null) {
                continue;
            }
            Set<String> tested = NumericClassSupport.numericTestedNames(source);
            DataFlowSupport support = DataFlowSupport.of(model, texts);
            List<Statement> all = new ArrayList<>();
            for (Procedure procedure : model.procedures()) {
                all.addAll(procedure.statements());
            }
            NumericClassSupport.walk(all, statement ->
                    evaluate(model, statement, support, tested, findings));
        }
        return findings;
    }

    private static void evaluate(CobolSemanticModel model, Statement statement,
            DataFlowSupport support, Set<String> tested, List<Finding> findings) {
        List<String> candidates;
        String use;
        if (statement instanceof SimpleStatement simple
                && ARITHMETIC_VERBS.contains(simple.verb().toUpperCase(Locale.ROOT))) {
            candidates = arithmeticOperands(simple.text());
            use = "演算";
        } else if (statement instanceof CompoundStatement compound
                && !compound.conditionText().isBlank()) {
            candidates = relationOperands(compound.conditionText());
            use = "比較";
        } else {
            return;
        }
        for (String candidate : candidates) {
            if (tested.contains(NumericClassSupport.norm(candidate))) {
                continue;
            }
            if (!isUntestedInputNumeric(support, candidate)) {
                continue;
            }
            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                    candidate + " は NUMERIC 検査を経ずに" + use + "に使われます。"
                            + "数字として不正な値のまま処理が進み得ます。",
                    new SourcePosition(model.sourceFile(), statement.range().start().line(), 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET)));
            return;
        }
    }

    private static boolean isUntestedInputNumeric(DataFlowSupport support, String name) {
        DataItem item = support.item(name).orElse(null);
        if (item == null || !item.children().isEmpty() || item.picture().isEmpty()) {
            return false;
        }
        PictureType picture = pictureOf(item);
        if (picture == null || !picture.isNumeric() || picture.usage() != Usage.DISPLAY) {
            return false;
        }
        DataFlowSupport.Section section = support.sectionOf(name);
        return section == DataFlowSupport.Section.FILE || section == DataFlowSupport.Section.LINKAGE;
    }

    private static PictureType pictureOf(DataItem item) {
        try {
            return PictureType.parse(item.picture().orElseThrow(), item.usage().orElse(null));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> arithmeticOperands(String statementText) {
        String masked = NumericClassSupport.maskParenthesized(
                NumericClassSupport.maskLiterals(statementText));
        List<String> operands = new ArrayList<>();
        Matcher m = NumericClassSupport.NAME_TOKEN.matcher(masked);
        while (m.find()) {
            String token = m.group();
            if (!RESERVED.contains(token.toUpperCase(Locale.ROOT))) {
                operands.add(token);
            }
        }
        return operands;
    }

    private static List<String> relationOperands(String conditionText) {
        String masked = NumericClassSupport.maskLiterals(conditionText);
        List<String> operands = new ArrayList<>();
        Matcher op = RELATION_OP.matcher(masked);
        while (op.find()) {
            String left = lastName(masked.substring(0, op.start()));
            if (left != null) {
                operands.add(left);
            }
            String right = firstName(masked.substring(op.end()));
            if (right != null) {
                operands.add(right);
            }
        }
        return operands;
    }

    private static String lastName(String region) {
        Matcher m = NumericClassSupport.NAME_TOKEN.matcher(region);
        String last = null;
        while (m.find()) {
            last = m.group();
        }
        return last;
    }

    private static String firstName(String region) {
        Matcher m = NumericClassSupport.NAME_TOKEN.matcher(region);
        return m.find() ? m.group() : null;
    }
}

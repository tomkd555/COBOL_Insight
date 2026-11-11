package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.picture.PictureCategory;
import jp.cobolinsight.core.picture.PictureType;
import jp.cobolinsight.core.picture.Usage;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.cfg.CfgSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R108 A group cleared with SPACES, LOW-VALUES or HIGH-VALUES whose numeric items the program then
 * computes with. A MOVE to a group is an alphanumeric move: it fills every byte of the group with
 * the same character, the packed and zoned decimal items included, and none of the three is a
 * valid digit and sign. The first ADD to such an item is a data exception.
 *
 * <p>Clearing an output record this way and then moving a value into each field is harmless and
 * very common, so the rule reports only an item that is an operand of an arithmetic statement and
 * that no statement of the program gives a value to on its own.
 */
public final class GroupClearedNumericRule implements Rule {

    private static final Pattern CLEAR = Pattern.compile(
            "(?is)^MOVE\\s+(SPACES?|LOW-VALUES?|HIGH-VALUES?)\\s+TO\\s+(.+?)\\.?\\s*$");

    private static final Set<String> ARITHMETIC =
            Set.of("ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "COMPUTE");

    private static final Set<String> SETTERS = Set.of("MOVE", "INITIALIZE", "ACCEPT");

    private static final RuleMeta META = RuleMeta
            .named("R108", "数字項目を含む集団項目への SPACE・LOW-VALUE 転記", RuleCategory.ABEND)
            .technology(Technology.COBOL)
            .symptom("S0C7")
            .summary("SPACES・LOW-VALUES・HIGH-VALUES を集団項目へ転記し、その下の数字項目を"
                    + "値を設定しないまま演算に使っているものを検出します。")
            .rationale("集団項目への MOVE は英数字の転記で、配下のパック 10 進・ゾーン 10 進の"
                    + "項目も同じ文字で埋めます。どれも数字として正しくないため、"
                    + "最初の演算でデータ例外（S0C7）になります。")
            .detection("MOVE SPACES・LOW-VALUES・HIGH-VALUES の受け取り側が集団項目のとき、"
                    + "その配下の USAGE が DISPLAY か COMP-3 の数字項目のうち、"
                    + "ADD・SUBTRACT・MULTIPLY・DIVIDE・COMPUTE の文に現れ、かつ"
                    + "MOVE・INITIALIZE・ACCEPT の受け取り側に一度も現れないものを検出します。"
                    + "2 進（COMP）の項目と、値を個別に設定している項目は対象外です。"
                    + "報告は MOVE 文につき 1 件です。")
            .remedy("INITIALIZE で項目の型に合わせて初期化するか、数字項目へ個別に ZERO を"
                    + "転記してください。")
            .example("""
                    01  WS-SYUKEI.
                        05 WS-TEN-CD     PIC X(4).
                        05 WS-KENSU      PIC S9(5) COMP-3.
                    ...
                        MOVE SPACES TO WS-SYUKEI.
                        ADD 1 TO WS-KENSU.
                    """, """
                        INITIALIZE WS-SYUKEI.
                        ADD 1 TO WS-KENSU.
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
            List<SimpleStatement> statements = new ArrayList<>();
            model.procedures().forEach(procedure ->
                    CfgSupport.walk(procedure.statements(), statement -> {
                        if (statement instanceof SimpleStatement simple) {
                            statements.add(simple);
                        }
                    }));
            for (SimpleStatement statement : statements) {
                Matcher clear = CLEAR.matcher(statement.text().strip());
                if (!"MOVE".equalsIgnoreCase(statement.verb()) || !clear.matches()) {
                    continue;
                }
                for (String target : clear.group(2).replace(",", " ").strip().split("\\s+")) {
                    DataItem group = CfgSupport.itemNamed(model, target).orElse(null);
                    if (group == null || group.children().isEmpty()) {
                        continue;
                    }
                    DataItem victim = firstUnsetNumeric(group, statements);
                    if (victim != null) {
                        findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                                clear.group(1).toUpperCase(Locale.ROOT) + " を集団項目 "
                                        + group.name() + " へ転記していますが、配下の数字項目 "
                                        + victim.name() + " は値を設定されないまま"
                                        + "演算に使われます。データ例外になります。",
                                CfgSupport.at(statement.range())));
                        break;
                    }
                }
            }
        }
        return findings;
    }

    /** The first numeric descendant the program computes with and never sets on its own. */
    private static DataItem firstUnsetNumeric(DataItem group, List<SimpleStatement> statements) {
        for (DataItem child : group.children()) {
            if (!child.children().isEmpty()) {
                DataItem nested = firstUnsetNumeric(child, statements);
                if (nested != null) {
                    return nested;
                }
                continue;
            }
            if (!decimal(child)) {
                continue;
            }
            Pattern name = CfgSupport.wordPattern(child.name());
            Pattern receives = Pattern.compile("(?is)\\b(?:TO|INTO)\\b[^.]*"
                    + name.pattern());
            boolean computed = false;
            boolean set = false;
            for (SimpleStatement statement : statements) {
                String verb = statement.verb().toUpperCase(Locale.ROOT);
                if (ARITHMETIC.contains(verb) && name.matcher(statement.text()).find()) {
                    computed = true;
                }
                if (SETTERS.contains(verb) && (verb.equals("MOVE") || verb.equals("ACCEPT")
                        ? (verb.equals("ACCEPT") ? name.matcher(statement.text()).find()
                                : receives.matcher(statement.text()).find())
                        : name.matcher(statement.text()).find())) {
                    set = true;
                }
            }
            if (computed && !set) {
                return child;
            }
        }
        return null;
    }

    /** A zoned or packed decimal item: the two whose bytes have to be digits. */
    private static boolean decimal(DataItem item) {
        if (item.picture().isEmpty() || item.redefines().isPresent()) {
            return false;
        }
        try {
            PictureType type = PictureType.parse(item.picture().get(), item.usage().orElse(null));
            return type.category() == PictureCategory.NUMERIC
                    && Usage.normalize(item.usage().orElse(null)) != Usage.BINARY;
        } catch (RuntimeException e) {
            return false;
        }
    }
}

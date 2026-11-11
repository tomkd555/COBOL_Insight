package jp.cobolinsight.rules.easytrieve;

import jp.cobolinsight.core.easytrieve.EasytrieveField;
import jp.cobolinsight.core.easytrieve.EasytrieveFile;
import jp.cobolinsight.core.easytrieve.EasytrieveProgram;
import jp.cobolinsight.core.easytrieve.EasytrieveStatement;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R100 A zoned-decimal field of an input file used in arithmetic with no NUMERIC test anywhere.
 * Easytrieve converts the field to packed decimal when it computes with it, and a record with
 * blanks or text in the field ends the job with a data exception — the commonest abend of an
 * Easytrieve job, and one that depends on the day's data.
 */
public final class NumericInputUncheckedRule implements Rule {

    private static final Set<String> ARITHMETIC = Set.of("+", "-", "*", "/");

    private static final RuleMeta META = RuleMeta
            .named("R100", "NUMERIC 検査のない入力数字項目の演算", RuleCategory.ABEND)
            .technology(Technology.EASYTRIEVE)
            .symptom("S0C7")
            .summary("入力ファイルのゾーン 10 進（N）項目を、NUMERIC の検査をしないまま"
                    + "演算に使っているものを検出します。")
            .rationale("項目に空白や文字が入ったレコードがあると、演算のための変換で"
                    + "データ例外（S0C7）になりジョブが止まります。その日のデータ次第で"
                    + "発生します。")
            .detection("プログラムが読むファイルに N で定義された項目のうち、代入文の右辺で"
                    + "四則演算の作用対象になっているものを対象にします。プログラムのどこにも"
                    + "その項目の NUMERIC・NOT NUMERIC の検査がないものを検出します。"
                    + "パック 10 進（P）・2 進（B）の項目、作業用の項目、出力専用のファイルの項目は"
                    + "対象外です。報告は項目につき最初の演算に 1 件です。")
            .remedy("演算の前に IF 項目 NOT NUMERIC でレコードを検査し、不正なレコードを"
                    + "エラーとして分けてください。")
            .example("""
                    W-GOKEI = W-GOKEI + U-KINGAKU
                    """, """
                    IF U-KINGAKU NOT NUMERIC
                       DISPLAY 'KINGAKU ERROR ' U-TEN
                       GOTO JOB
                    END-IF
                    W-GOKEI = W-GOKEI + U-KINGAKU
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.EASYTRIEVE, AssetKind.JCL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (EasytrieveProgram program : EasytrieveSupport.programs(context)) {
            Map<String, EasytrieveField> inputs = new LinkedHashMap<>();
            for (EasytrieveFile file : program.files()) {
                String access = program.fileAccess().getOrDefault(file.name(), "");
                if (access.equals("READ") || access.equals("UPDATE")) {
                    file.fields().stream().filter(field -> field.type().equals("N"))
                            .forEach(field -> inputs.putIfAbsent(field.name(), field));
                }
            }
            if (inputs.isEmpty()) {
                continue;
            }
            List<EasytrieveStatement> statements = program.executableStatements();
            Set<String> tested = new HashSet<>();
            for (EasytrieveStatement statement : statements) {
                int numeric = statement.indexOf("NUMERIC");
                if (numeric > 0) {
                    // IF field NUMERIC, IF field NOT NUMERIC: the field stands before the test.
                    tested.add(unqualified(statement.word(numeric - 1).equals("NOT")
                            ? statement.word(numeric - 2) : statement.word(numeric - 1)));
                }
            }
            Set<String> reported = new HashSet<>();
            for (EasytrieveStatement statement : statements) {
                if (!statement.isAssignment()) {
                    continue;
                }
                List<String> words = statement.words();
                for (int i = 2; i < words.size(); i++) {
                    String name = unqualified(words.get(i));
                    boolean operand = (i > 2 && ARITHMETIC.contains(words.get(i - 1)))
                            || (i + 1 < words.size() && ARITHMETIC.contains(words.get(i + 1)));
                    if (operand && inputs.containsKey(name) && !tested.contains(name)
                            && reported.add(name)) {
                        findings.add(EasytrieveSupport.finding(META,
                                EasytrieveSupport.at(statement), "入力項目 " + name
                                        + "（N）を NUMERIC の検査なしで演算に使っています。"
                                        + "数字でない値のレコードでデータ例外になります。"));
                    }
                }
            }
        }
        return findings;
    }

    /** {@code FILE:FIELD} names the field of one file; the field is what the rule looks up. */
    private static String unqualified(String word) {
        int colon = word.indexOf(':');
        return colon >= 0 ? word.substring(colon + 1) : word;
    }
}

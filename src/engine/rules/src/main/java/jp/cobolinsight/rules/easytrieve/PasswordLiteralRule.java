package jp.cobolinsight.rules.easytrieve;

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
import java.util.List;

/**
 * R103 A password written in the program. A FILE statement may carry the password of a VSAM data
 * set, and a PARM statement the user and password of a database connection, as literals. The
 * source is readable by everyone who can browse the library and is printed with every compile
 * listing.
 */
public final class PasswordLiteralRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R103", "Easytrieve の原始プログラムに書かれたパスワード", RuleCategory.SECURITY)
            .technology(Technology.EASYTRIEVE)
            .symptom("資格情報の漏えい")
            .summary("FILE 文の PASSWORD と PARM 文の USERID に定数で書かれたパスワードを"
                    + "検出します。")
            .rationale("原始プログラムはライブラリーを参照できる全員が読め、コンパイルリストにも"
                    + "印字されます。")
            .detection("FILE 文の PASSWORD の後ろに文字定数があるものと、PARM 文の USERID の"
                    + "括弧の中に文字定数が 2 つ以上あるものを検出します。")
            .remedy("データセットの保護は RACF などのセキュリティー製品に任せ、パスワードを"
                    + "原始プログラムに書かないでください。")
            .severity(Severity.HIGH)
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
            for (EasytrieveFile file : program.files()) {
                int at = file.options().indexOf("PASSWORD");
                if (at >= 0 && at + 1 < file.options().size()
                        && file.options().get(at + 1).contains("'")) {
                    findings.add(EasytrieveSupport.finding(META, file.position(),
                            "ファイル " + file.name() + " の FILE 文に PASSWORD が定数で"
                                    + "書かれています。原始プログラムを読める全員に見えます。"));
                }
            }
            for (EasytrieveStatement parm : program.parms()) {
                int at = parm.indexOf("USERID");
                if (at < 0) {
                    continue;
                }
                long literals = parm.words().stream().skip(at + 1L).takeWhile(w -> !w.equals(")"))
                        .filter(word -> word.startsWith("'")).count();
                if (literals >= 2) {
                    findings.add(EasytrieveSupport.finding(META, EasytrieveSupport.at(parm),
                            "PARM 文の USERID にパスワードが定数で書かれています。"
                                    + "原始プログラムを読める全員に見えます。"));
                }
            }
        }
        return findings;
    }
}

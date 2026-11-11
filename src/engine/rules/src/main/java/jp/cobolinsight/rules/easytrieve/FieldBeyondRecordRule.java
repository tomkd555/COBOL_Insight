package jp.cobolinsight.rules.easytrieve;

import jp.cobolinsight.core.easytrieve.EasytrieveField;
import jp.cobolinsight.core.easytrieve.EasytrieveFile;
import jp.cobolinsight.core.easytrieve.EasytrieveProgram;
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
import java.util.List;
import java.util.Set;

/**
 * R098 A field defined past the end of its record. The FILE statement states the record length,
 * and a field whose start and length carry it beyond that length overlays whatever follows the
 * record in the buffer: the next record of the block, or storage that is not the program's.
 * Reading it yields another record's bytes; writing it destroys them.
 */
public final class FieldBeyondRecordRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R098", "レコード長を超える項目定義", RuleCategory.INTEGRITY)
            .technology(Technology.EASYTRIEVE)
            .symptom("隣のレコードの参照・破壊、S0C4・S0C7")
            .summary("FILE 文のレコード長を超える位置まで及ぶ項目定義を検出します。")
            .rationale("レコードの外のバッファーを参照します。読めば次のレコードの内容や"
                    + "不定の値が入り、書けばそれを壊します。")
            .detection("FILE 文が F・FB・V・VB などのレコード形式とレコード長を書いているファイルに"
                    + "ついて、開始位置と長さ（OCCURS があれば全回数分）から求めた項目の終わりが"
                    + "レコード長を超えるものを検出します。開始位置か長さを求められない項目と、"
                    + "レコード長を書かないファイルは対象外です。可変長（V・VB）は、先頭 4 バイトの"
                    + "レコード記述語を除いた長さと比べます。")
            .remedy("項目の開始位置と長さを見直すか、FILE 文のレコード長を実際のレコードに"
                    + "合わせてください。")
            .example("""
                    FILE URIAGE FB(100 1000)
                      U-BIKO   95  10 A
                    """, """
                    FILE URIAGE FB(100 1000)
                      U-BIKO   91  10 A
                    """)
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
        Set<String> reportedAt = new HashSet<>();
        for (EasytrieveProgram program : EasytrieveSupport.programs(context)) {
            for (EasytrieveFile file : program.files()) {
                if (file.recordLength().isEmpty()) {
                    continue;
                }
                boolean variable = file.recordFormat().orElse("").startsWith("V");
                int length = file.recordLength().get() - (variable ? 4 : 0);
                for (EasytrieveField field : file.fields()) {
                    int end = field.end().orElse(0);
                    // A field a macro defines is the same line for every program that invokes it.
                    if (end > length && reportedAt.add(field.position().file() + ":"
                            + field.position().line() + ":" + file.name())) {
                        findings.add(EasytrieveSupport.finding(META, field.position(),
                                "項目 " + field.name() + " は " + field.start().orElse(0)
                                        + " 桁目から " + end + " 桁目までですが、ファイル "
                                        + file.name() + " のレコード長は " + length
                                        + " です。レコードの外を参照します。"));
                    }
                }
            }
        }
        return findings;
    }
}

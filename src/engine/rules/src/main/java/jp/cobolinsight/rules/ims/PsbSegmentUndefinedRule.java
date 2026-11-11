package jp.cobolinsight.rules.ims;

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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * R090 A PSB that names a segment its database does not have. Each SENSEG of a PCB has to be a
 * segment of the DBD the PCB names, under the parent the DBD gives it. A name left over from an
 * earlier database design, or a parent changed on one side only, fails the ACB generation — and a
 * PSB whose ACB was not rebuilt keeps the old view, so the program runs against a structure the
 * database no longer has.
 *
 * <p>A PCB whose DBD is not among the assets is not judged: there is nothing to compare it with.
 */
public final class PsbSegmentUndefinedRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R090", "DBD に定義のないセグメントを指す PSB", RuleCategory.ABEND)
            .technology(Technology.IMS)
            .symptom("ACBGEN エラー・状態コード AK・GE")
            .summary("PSB の SENSEG が指すセグメントのうち、PCB の DBD に定義されていないものと、"
                    + "親セグメントが DBD と合わないものを検出します。")
            .rationale("ACB の生成が失敗するか、古い ACB のままプログラムが動き、"
                    + "データベースに存在しない構造を前提に DL/I 呼び出しを出します。")
            .detection("PSB の各 DB PCB について、DBDNAME の DBD が解析対象にあるとき、"
                    + "SENSEG の NAME がその DBD の SEGM にあるか、PARENT が DBD の親と一致するかを"
                    + "確かめます。DBD が解析対象にない PCB と、TP・GSAM の PCB は対象外です。")
            .remedy("SENSEG の名前と PARENT を DBD の SEGM に合わせ、PSBGEN と ACBGEN を"
                    + "やり直してください。")
            .example("""
                    PCB   TYPE=DB,DBDNAME=CUSTDBD,PROCOPT=A,KEYLEN=18
                    SENSEG NAME=CUSTROOT,PARENT=0
                    SENSEG NAME=SHIPMENT,PARENT=CUSTROOT
                    """, """
                    PCB   TYPE=DB,DBDNAME=CUSTDBD,PROCOPT=A,KEYLEN=18
                    SENSEG NAME=CUSTROOT,PARENT=0
                    SENSEG NAME=ORDER,PARENT=CUSTROOT
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT)
            .targets(AssetKind.IMS_PSB, AssetKind.IMS_DBD)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        Map<String, jp.cobolinsight.core.ims.ImsDbd> dbds = new java.util.HashMap<>();
        context.imsDbds().forEach(dbd ->
                dbds.putIfAbsent(dbd.name().toUpperCase(Locale.ROOT), dbd));
        List<Finding> findings = new ArrayList<>();
        for (jp.cobolinsight.core.ims.ImsPsb psb : context.imsPsbs()) {
            for (jp.cobolinsight.core.ims.ImsPsb.Pcb pcb : psb.pcbs()) {
                jp.cobolinsight.core.ims.ImsDbd dbd = pcb.dbdName()
                        .map(name -> dbds.get(name.toUpperCase(Locale.ROOT))).orElse(null);
                if (dbd == null || !pcb.type().equals("DB")) {
                    continue;
                }
                for (jp.cobolinsight.core.ims.ImsPsb.Senseg senseg : pcb.segments()) {
                    Optional<jp.cobolinsight.core.ims.ImsDbd.Segment> segment =
                            dbd.segments().stream()
                                    .filter(s -> s.name().equalsIgnoreCase(senseg.name()))
                                    .findFirst();
                    if (segment.isEmpty()) {
                        findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                                "PSB " + psb.name() + " の SENSEG " + senseg.name() + " は、DBD "
                                        + dbd.name() + " に定義されていません。"
                                        + "ACB の生成が失敗します。",
                                senseg.position()));
                    } else if (senseg.parent().isPresent() && !senseg.parent().map(String::trim)
                            .equals(segment.get().parent().map(String::trim))) {
                        findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                                "PSB " + psb.name() + " の SENSEG " + senseg.name()
                                        + " の PARENT は " + senseg.parent().get()
                                        + " ですが、DBD " + dbd.name() + " では "
                                        + segment.get().parent().orElse("ルート（PARENT=0）")
                                        + " です。ACB の生成が失敗します。",
                                senseg.position()));
                    }
                }
            }
        }
        return findings;
    }
}

package jp.cobolinsight.rules.cics;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.cfg.CfgSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * R085 COBOL file I/O, or an ACCEPT from the operator, in a CICS program. CICS runs every task of
 * a region on one program-control thread: a COBOL OPEN, READ or WRITE issues an operating-system
 * wait that stops that thread, and with it every other transaction of the region, until the I/O
 * completes. Files are read through CICS file control and transient data, which suspend only the
 * task that asked.
 *
 * <p>{@code ACCEPT … FROM DATE}, {@code DAY} and {@code TIME} read the system clock and wait for
 * nobody, so they are not reported.
 */
public final class CicsNativeIoRule implements Rule {

    private static final Set<String> FILE_VERBS =
            Set.of("OPEN", "CLOSE", "READ", "WRITE", "REWRITE", "START", "DELETE");

    private static final Pattern ACCEPT_FROM_CLOCK = Pattern.compile(
            "(?is)\\bFROM\\s+(DATE|DAY|DAY-OF-WEEK|TIME)\\b");

    private static final RuleMeta META = RuleMeta
            .named("R085", "CICS プログラムでの COBOL 入出力文・ACCEPT", RuleCategory.PERFORMANCE)
            .technology(Technology.CICS)
            .symptom("領域全体の応答停止")
            .summary("EXEC CICS を持つプログラムの、COBOL のファイル入出力文（OPEN・READ・WRITE"
                    + " など）と、操作員からの ACCEPT を検出します。")
            .rationale("COBOL の入出力文はオペレーティングシステムの待ちを発行し、入出力が"
                    + "終わるまで CICS 領域のすべてのトランザクションを止めます。")
            .detection("EXEC CICS のコマンドを持ち、FILE-CONTROL にファイルを定義している"
                    + "プログラムについて、OPEN・CLOSE・READ・WRITE・REWRITE・START・DELETE の文を"
                    + "検出します。あわせて、FROM DATE・DAY・DAY-OF-WEEK・TIME を伴わない"
                    + "ACCEPT 文を検出します。1 プログラムにつき、文の種類ごとに最初の 1 件を"
                    + "報告します。")
            .remedy("ファイルは EXEC CICS READ・WRITE（ファイル制御）か一時データキューで"
                    + "扱ってください。入力は EXEC CICS RECEIVE で受け取ってください。")
            .example("""
                    OPEN INPUT ZAIKO-FILE.
                    READ ZAIKO-FILE INTO WS-REC.
                    """, """
                    EXEC CICS READ FILE('ZAIKO') INTO(WS-REC) RIDFLD(WS-KEY)
                         RESP(WS-RESP) END-EXEC.
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
            if (!CicsCommands.isCicsProgram(model)) {
                continue;
            }
            boolean hasFiles = !model.files().isEmpty();
            Map<String, SimpleStatement> firstByVerb = new LinkedHashMap<>();
            model.procedures().forEach(procedure ->
                    CfgSupport.walk(procedure.statements(), statement -> {
                        if (!(statement instanceof SimpleStatement simple)) {
                            return;
                        }
                        String verb = simple.verb().toUpperCase(Locale.ROOT);
                        boolean fileIo = hasFiles && FILE_VERBS.contains(verb);
                        boolean console = verb.equals("ACCEPT")
                                && !ACCEPT_FROM_CLOCK.matcher(simple.text()).find();
                        if (fileIo || console) {
                            firstByVerb.putIfAbsent(verb, simple);
                        }
                    }));
            firstByVerb.forEach((verb, statement) -> findings.add(Finding.of(META.id(),
                    META.defaultSeverity().toLevel(),
                    model.programId() + " は CICS のもとで動きますが、COBOL の " + verb
                            + " 文を実行しています。入出力が終わるまで、"
                            + "領域のすべてのトランザクションが止まります。",
                    CfgSupport.at(statement.range()))));
        }
        return findings;
    }
}

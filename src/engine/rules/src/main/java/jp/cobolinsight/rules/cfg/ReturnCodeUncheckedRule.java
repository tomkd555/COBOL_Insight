package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CallRelation;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * R029 Unchecked RETURN-CODE after a CALL. Detects a CALL statement whose forward path, up to
 * the next CALL or the program's terminal point, never references RETURN-CODE in a condition.
 * This applies only when the program references RETURN-CODE at least once elsewhere (catching
 * the inconsistency of a design that uses RETURN-CODE everywhere except this one unchecked
 * CALL).
 */
public final class ReturnCodeUncheckedRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R029", "呼び出し先プログラムの戻りコード（RETURN-CODE）未検査", RuleCategory.INTEGRITY)
            .technology(Technology.COBOL)
            .symptom("呼び出し先の失敗の見逃し")
            .summary("RETURN-CODE を使う設計のプログラムで、"
                    + "その検査を伴わない CALL 文を検出します。")
            .rationale("呼び出し先の失敗に気づかないまま後続が進みます。")
            .detection("CALL 文の後、次の CALL 文または終端に達するまでの前方経路で"
                    + "RETURN-CODE を条件で参照しないものを検出します。"
                    + "同じプログラムの他の箇所に RETURN-CODE の参照があることを前提とし、"
                    + "RETURN-CODE をどこでも参照しないプログラムは対象外です。"
                    + "CBLTDLI・MQGET・DSNTIAR・CEE で始まる呼び出しなど、結果を引数や"
                    + "状態コードで返すミドルウェアと言語実行系の入口点の CALL も対象外です。")
            .remedy("CALL 文の直後に RETURN-CODE を検査し、正常値以外を異常として"
                    + "処理してください。")
            .example("""
                    CALL "SUBPGM2" USING WS-PARM.
                    MOVE WS-PARM TO WS-OUT.
                    """, """
                    CALL "SUBPGM2" USING WS-PARM.
                    IF RETURN-CODE NOT = ZERO
                        PERFORM CALL-ERROR
                    END-IF.
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .needs(Needs.CFG)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        ControlFlowGraphs cfgs = context.artifact(ControlFlowGraphs.class).orElse(null);
        if (cfgs == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            if (!referencesReturnCode(model)) {
                continue;
            }
            cfgs.of(model).ifPresent(cfg -> evaluate(model, cfg, findings));
        }
        return findings;
    }

    private void evaluate(CobolSemanticModel model, ControlFlowGraph cfg, List<Finding> findings) {
        for (CfgNode node : cfg.nodes()) {
            if (!isCall(node)) {
                continue;
            }
            boolean checked = CfgSupport.forwardHasMatch(cfg, node,
                    ReturnCodeUncheckedRule::isCall,
                    ReturnCodeUncheckedRule::referencesReturnCodeInCondition);
            if (!checked) {
                Statement call = node.statement().orElseThrow();
                int line = call.range().start().line();
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        subjectOf(model, call) + " RETURN-CODE を検査していません。"
                                + "呼び出し先の失敗に気づかないまま後続が進みます。",
                        CfgSupport.at(call.range(), line)));
            }
        }
    }

    /**
     * The CALL as the semantic model resolved it, its relation starting where the statement does
     * in the statement's own file, or the bare statement.
     */
    private static String subjectOf(CobolSemanticModel model, Statement call) {
        return model.calls().stream()
                .filter(relation -> relation.range().start().equals(call.range().start()))
                .map(CallRelation::target)
                .findFirst()
                .map(target -> "CALL " + target + " の後で")
                .orElse("CALL 文の後で");
    }

    /** The literal a CALL names, when it names one. */
    private static final java.util.regex.Pattern LITERAL_TARGET = java.util.regex.Pattern.compile(
            "(?is)^CALL\\s+['\"]([^'\"]+)['\"]");

    /**
     * A CALL of an application program. An entry point of IMS, MQ, Db2, CICS or the language
     * run-time reports its outcome in a parameter or a status area of its own and leaves
     * RETURN-CODE alone, so asking for RETURN-CODE after it would be asking for the wrong thing.
     */
    private static boolean isCall(CfgNode node) {
        return node.statement()
                .map(statement -> statement instanceof SimpleStatement simple
                        && "CALL".equals(CfgSupport.upper(simple.verb()))
                        && !callsSystemService(simple))
                .orElse(false);
    }

    private static boolean callsSystemService(SimpleStatement call) {
        java.util.regex.Matcher target = LITERAL_TARGET.matcher(call.text().strip());
        return target.find() && jp.cobolinsight.analysis.linker.CallGraphLinker
                .systemServiceOf(target.group(1)).isPresent();
    }

    private static boolean referencesReturnCodeInCondition(CfgNode node) {
        return node.statement()
                .map(statement -> statement instanceof CompoundStatement compound
                        && compound.conditionText().toUpperCase(Locale.ROOT)
                                .contains("RETURN-CODE"))
                .orElse(false);
    }

    private static boolean referencesReturnCode(CobolSemanticModel model) {
        AtomicBoolean found = new AtomicBoolean(false);
        for (Procedure procedure : model.procedures()) {
            CfgSupport.walk(procedure.statements(), statement -> {
                if (mentionsReturnCode(statement)) {
                    found.set(true);
                }
            });
        }
        return found.get();
    }

    private static boolean mentionsReturnCode(Statement statement) {
        String text = switch (statement) {
            case SimpleStatement simple -> simple.text();
            case CompoundStatement compound -> compound.conditionText();
            default -> "";
        };
        return text.toUpperCase(Locale.ROOT).contains("RETURN-CODE");
    }
}

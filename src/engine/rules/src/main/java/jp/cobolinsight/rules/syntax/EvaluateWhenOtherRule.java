package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.ControlKind;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.List;

/**
 * R013 EVALUATE statement missing WHEN OTHER. Detects places where an EVALUATE statement has
 * no WHEN OTHER clause. Without a WHEN OTHER clause, a value that matches none of the WHEN
 * clauses passes through without any handling. In the semantic model, an EVALUATE statement
 * is a branch (BRANCH) compound statement, distinguished from an IF statement, whose first
 * block's label is {@code "THEN"}. A WHEN OTHER clause appears as a block labeled {@code "OTHER"}.
 */
public final class EvaluateWhenOtherRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("R013", "EVALUATE 文の WHEN OTHER 句欠如", "制御フロー")
            .summary("WHEN OTHER 句を持たない EVALUATE 文を検出します。")
            .rationale("どの WHEN 句にも一致しない値は処理を受けずに通り抜け、"
                    + "想定外の入力が記録も通知もないまま無視されます。")
            .detection("EVALUATE 文のうち WHEN OTHER 句を持たないものを検出します。"
                    + "IF 文は対象外です。")
            .remedy("WHEN OTHER 句を置き、想定外の値に対する処理（異常扱い・既定値の設定）を"
                    + "書いてください。")
            .example("""
                    EVALUATE WS-KBN
                        WHEN "1" PERFORM SHINKI-SHORI
                        WHEN "2" PERFORM HENKO-SHORI
                    END-EVALUATE.
                    """, """
                    EVALUATE WS-KBN
                        WHEN "1" PERFORM SHINKI-SHORI
                        WHEN "2" PERFORM HENKO-SHORI
                        WHEN OTHER PERFORM ERROR-SHORI
                    END-EVALUATE.
                    """)
            .severity(Severity.MEDIUM)
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
            Statements.walk(model, statement -> {
                if (!(statement instanceof CompoundStatement compound)
                        || compound.kind() != ControlKind.BRANCH || isIf(compound)) {
                    return;
                }
                boolean hasOther = compound.blocks().stream()
                        .anyMatch(block -> "OTHER".equals(block.label()));
                if (!hasOther) {
                    findings.add(Finding.of("R013", Severity.MEDIUM.toLevel(),
                            subjectOf(compound) + " に WHEN OTHER 句がありません。"
                                    + "一致しない値は処理を受けずに通り抜けます。",
                            compound.range().start()));
                }
            });
        }
        return findings;
    }

    /**
     * The identifier the message opens with: the first EVALUATE subject where the parse gives
     * one (the ALSO subjects are elided so the headline stays short), and the reserved word alone
     * where it does not.
     */
    private static String subjectOf(CompoundStatement compound) {
        String subject = compound.conditionText().replaceAll("\\s+", " ").trim();
        if (subject.isEmpty()) {
            return "EVALUATE 文";
        }
        String[] subjects = subject.split("(?i)\\bALSO\\b");
        return "EVALUATE " + subjects[0].trim() + (subjects.length > 1 ? " ALSO …" : "");
    }

    private static boolean isIf(CompoundStatement compound) {
        List<StatementBlock> blocks = compound.blocks();
        return !blocks.isEmpty() && "THEN".equals(blocks.get(0).label());
    }
}

package jp.cobolinsight.rules;

import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.rules.cfg.AlterStatementRule;
import jp.cobolinsight.rules.cfg.CicsResponseUncheckedRule;
import jp.cobolinsight.rules.cfg.CicsReturnMissingRule;
import jp.cobolinsight.rules.cfg.CursorNotClosedRule;
import jp.cobolinsight.rules.cfg.FileStatusUncheckedRule;
import jp.cobolinsight.rules.cfg.GoToStructureDeviationRule;
import jp.cobolinsight.rules.cfg.JclCondUncheckedRule;
import jp.cobolinsight.rules.cfg.PerformThruInterruptGoToRule;
import jp.cobolinsight.rules.cfg.ReturnCodeUncheckedRule;
import jp.cobolinsight.rules.cfg.SectionFallThroughRule;
import jp.cobolinsight.rules.cfg.SqlCodeUncheckedRule;
import jp.cobolinsight.rules.cfg.UndefinedBmsMapReferenceRule;
import jp.cobolinsight.rules.cfg.UnreachableCodeRule;
import jp.cobolinsight.rules.dataflow.DynamicSqlTaintRule;
import jp.cobolinsight.rules.dataflow.IdenticalOperandsRule;
import jp.cobolinsight.rules.dataflow.MoveTruncationRule;
import jp.cobolinsight.rules.dataflow.OccursSubscriptRangeRule;
import jp.cobolinsight.rules.dataflow.OnSizeErrorMissingRule;
import jp.cobolinsight.rules.dataflow.PerformUntilNotUpdatedRule;
import jp.cobolinsight.rules.dataflow.RedefinesMismatchRule;
import jp.cobolinsight.rules.dataflow.SensitiveDataOutputRule;
import jp.cobolinsight.rules.dataflow.StringOverflowRule;
import jp.cobolinsight.rules.dataflow.UninitializedVariableRule;
import jp.cobolinsight.rules.dataflow.UnsignedNegativeResultRule;
import jp.cobolinsight.rules.sql.CursorDeclarationRule;
import jp.cobolinsight.rules.sql.FetchFirstMissingRule;
import jp.cobolinsight.rules.sql.FunctionOnIndexColumnRule;
import jp.cobolinsight.rules.sql.NonSargablePredicateRule;
import jp.cobolinsight.rules.sql.OptimizeForMissingRule;
import jp.cobolinsight.rules.sql.SelectStarRule;
import jp.cobolinsight.rules.syntax.BinarySubscriptRule;
import jp.cobolinsight.rules.syntax.CopyReplacingRule;
import jp.cobolinsight.rules.syntax.DuplicateProcedureNameRule;
import jp.cobolinsight.rules.syntax.EvaluateWhenOtherRule;
import jp.cobolinsight.rules.syntax.HardcodedCredentialRule;
import jp.cobolinsight.rules.syntax.PerformSingleParagraphRule;
import jp.cobolinsight.rules.syntax.UnusedDataItemRule;

import java.util.List;

/**
 * The built-in rule catalogue. Adding a rule means adding a line here — there is no runtime
 * discovery, so a rule that is missing from this list cannot fail silently at the end of a scan;
 * it simply does not compile into the product.
 */
public final class BuiltinRules {

    private BuiltinRules() {
    }

    public static List<Rule> all() {
        return List.of(
                new UninitializedVariableRule(),
                new UnusedDataItemRule(),
                new MoveTruncationRule(),
                new OnSizeErrorMissingRule(),
                new OccursSubscriptRangeRule(),
                new BinarySubscriptRule(),
                new PerformThruInterruptGoToRule(),
                new PerformSingleParagraphRule(),
                new GoToStructureDeviationRule(),
                new AlterStatementRule(),
                new UnreachableCodeRule(),
                new PerformUntilNotUpdatedRule(),
                new EvaluateWhenOtherRule(),
                new SectionFallThroughRule(),
                new RedefinesMismatchRule(),
                new StringOverflowRule(),
                new FileStatusUncheckedRule(),
                new SqlCodeUncheckedRule(),
                new CursorNotClosedRule(),
                new DynamicSqlTaintRule(),
                new CicsResponseUncheckedRule(),
                new CicsReturnMissingRule(),
                new DuplicateProcedureNameRule(),
                new CopyReplacingRule(),
                new IdenticalOperandsRule(),
                new HardcodedCredentialRule(),
                new SensitiveDataOutputRule(),
                new UnsignedNegativeResultRule(),
                new ReturnCodeUncheckedRule(),
                new JclCondUncheckedRule(),
                new UndefinedBmsMapReferenceRule(),
                new SelectStarRule(),
                new NonSargablePredicateRule(),
                new FunctionOnIndexColumnRule(),
                new CursorDeclarationRule(),
                new FetchFirstMissingRule(),
                new OptimizeForMissingRule());
    }
}

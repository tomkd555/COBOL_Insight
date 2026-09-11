package jp.cobolinsight.rules;

import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.rules.cfg.AlterStatementRule;
import jp.cobolinsight.rules.cfg.CicsResponseUncheckedRule;
import jp.cobolinsight.rules.cfg.CicsReturnMissingRule;
import jp.cobolinsight.rules.cfg.CommareaLengthMismatchRule;
import jp.cobolinsight.rules.cfg.CursorHoldCommitRule;
import jp.cobolinsight.rules.cfg.CursorNotClosedRule;
import jp.cobolinsight.rules.cfg.EibcalenUncheckedRule;
import jp.cobolinsight.rules.cfg.FileStatusUncheckedRule;
import jp.cobolinsight.rules.cfg.GoToStructureDeviationRule;
import jp.cobolinsight.rules.cfg.HandleAbendRollbackMissingRule;
import jp.cobolinsight.rules.cfg.JclCondUncheckedRule;
import jp.cobolinsight.rules.cfg.JclDispositionRule;
import jp.cobolinsight.rules.cfg.JclDuplicateDdNameRule;
import jp.cobolinsight.rules.cfg.JclFinalStepSkippedOnSuccessRule;
import jp.cobolinsight.rules.cfg.JclJobOperatorParameterRule;
import jp.cobolinsight.rules.cfg.JclMissingDdForProgramRule;
import jp.cobolinsight.rules.cfg.JclUndefinedReferenceRule;
import jp.cobolinsight.rules.cfg.JclUndefinedStepReferenceRule;
import jp.cobolinsight.rules.cfg.MapfailUncheckedRule;
import jp.cobolinsight.rules.cfg.PerformThruInterruptGoToRule;
import jp.cobolinsight.rules.cfg.ReferenceModificationOutOfRangeRule;
import jp.cobolinsight.rules.cfg.ReturnCodeUncheckedRule;
import jp.cobolinsight.rules.cfg.SectionFallThroughRule;
import jp.cobolinsight.rules.cfg.SqlCodeUncheckedRule;
import jp.cobolinsight.rules.cfg.TemporaryStorageNotDeletedRule;
import jp.cobolinsight.rules.cfg.UnanalyzedProgramCallRule;
import jp.cobolinsight.rules.cfg.UndefinedBmsMapReferenceRule;
import jp.cobolinsight.rules.cfg.UndefinedSymbolicMapItemRule;
import jp.cobolinsight.rules.cfg.UnreachableCodeRule;
import jp.cobolinsight.rules.dataflow.CallUsingLengthMismatchRule;
import jp.cobolinsight.rules.dataflow.DynamicSqlTaintRule;
import jp.cobolinsight.rules.dataflow.IdenticalOperandsRule;
import jp.cobolinsight.rules.dataflow.MoveTruncationRule;
import jp.cobolinsight.rules.dataflow.NumericClassUncheckedMoveRule;
import jp.cobolinsight.rules.dataflow.OccursSubscriptRangeRule;
import jp.cobolinsight.rules.dataflow.OnSizeErrorMissingRule;
import jp.cobolinsight.rules.dataflow.PerformUntilNotUpdatedRule;
import jp.cobolinsight.rules.dataflow.RedefinesMismatchRule;
import jp.cobolinsight.rules.dataflow.SensitiveDataOutputRule;
import jp.cobolinsight.rules.dataflow.StringOverflowRule;
import jp.cobolinsight.rules.dataflow.UninitializedVariableRule;
import jp.cobolinsight.rules.dataflow.UnsignedNegativeResultRule;
import jp.cobolinsight.rules.dataflow.UntestedNumericInputArithmeticRule;
import jp.cobolinsight.rules.sql.CursorDeclarationRule;
import jp.cobolinsight.rules.sql.CursorLifecycleRule;
import jp.cobolinsight.rules.sql.HostVariableTypeRule;
import jp.cobolinsight.rules.sql.InsertWithoutColumnListRule;
import jp.cobolinsight.rules.sql.NonSargablePredicateRule;
import jp.cobolinsight.rules.sql.NullIndicatorMissingRule;
import jp.cobolinsight.rules.sql.SchemaQualifiedTableRule;
import jp.cobolinsight.rules.sql.SelectStarRule;
import jp.cobolinsight.rules.sql.UnboundedSelectIntoRule;
import jp.cobolinsight.rules.sql.UncommittedUpdateLoopRule;
import jp.cobolinsight.rules.sql.UndeclaredColumnRule;
import jp.cobolinsight.rules.sql.UnqualifiedUpdateRule;
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
                new JclDispositionRule(),
                new SelectStarRule(),
                new NonSargablePredicateRule(),
                new CursorDeclarationRule(),
                new NullIndicatorMissingRule(),
                new HostVariableTypeRule(),
                new CursorHoldCommitRule(),
                new UncommittedUpdateLoopRule(),
                new UndefinedSymbolicMapItemRule(),
                new EibcalenUncheckedRule(),
                new CommareaLengthMismatchRule(),
                new HandleAbendRollbackMissingRule(),
                new MapfailUncheckedRule(),
                new TemporaryStorageNotDeletedRule(),
                new NumericClassUncheckedMoveRule(),
                new CallUsingLengthMismatchRule(),
                new ReferenceModificationOutOfRangeRule(),
                new UntestedNumericInputArithmeticRule(),
                new JclFinalStepSkippedOnSuccessRule(),
                new JclUndefinedStepReferenceRule(),
                new UnanalyzedProgramCallRule(),
                new JclUndefinedReferenceRule(),
                new JclDuplicateDdNameRule(),
                new JclMissingDdForProgramRule(),
                new JclJobOperatorParameterRule(),
                new UnqualifiedUpdateRule(),
                new CursorLifecycleRule(),
                new UnboundedSelectIntoRule(),
                new UndeclaredColumnRule(),
                new SchemaQualifiedTableRule(),
                new InsertWithoutColumnListRule());
    }
}

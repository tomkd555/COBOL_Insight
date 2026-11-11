package jp.cobolinsight.rules.easytrieve;

import jp.cobolinsight.core.easytrieve.EasytrieveActivity;
import jp.cobolinsight.core.easytrieve.EasytrieveProgram;
import jp.cobolinsight.core.easytrieve.EasytrieveReport;
import jp.cobolinsight.core.easytrieve.EasytrieveStatement;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the Easytrieve rules share: which programs a rule judges, where a statement stands, and
 * the lists of statements one after another that the flow-like rules read.
 *
 * <p>The language has no nested syntax tree in the model, only statements in order with the depth
 * of the IF, DO and CASE blocks around them. That is enough for what these rules ask — what stands
 * right after a statement, and which block a statement closes.
 */
final class EasytrieveSupport {

    private EasytrieveSupport() {
    }

    /** The programs a rule judges: a macro member is judged through the programs that invoke it. */
    static List<EasytrieveProgram> programs(AnalysisContext context) {
        return context.easytrievePrograms().stream()
                .filter(program -> !program.macroMember()).toList();
    }

    static SourcePosition at(EasytrieveStatement statement) {
        return new SourcePosition(statement.file(), statement.line(), 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
    }

    static Finding finding(RuleMeta meta, SourcePosition position, String message) {
        return Finding.of(meta.id(), meta.defaultSeverity().toLevel(), message, position);
    }

    /**
     * Every list of statements that runs in sequence: the body of each activity, and of each
     * procedure of an activity or of a report.
     */
    static List<List<EasytrieveStatement>> sequences(EasytrieveProgram program) {
        List<List<EasytrieveStatement>> sequences = new ArrayList<>();
        for (EasytrieveActivity activity : program.activities()) {
            sequences.add(activity.statements());
            activity.procedures().forEach(procedure -> sequences.add(procedure.statements()));
            for (EasytrieveReport report : activity.reports()) {
                report.procedures().forEach(procedure -> sequences.add(procedure.statements()));
            }
        }
        return sequences;
    }

    /** The DO that the END-DO at {@code index} closes, or empty. */
    static Optional<EasytrieveStatement> openingDo(List<EasytrieveStatement> statements,
            int index) {
        int depth = statements.get(index).depth();
        for (int i = index - 1; i >= 0; i--) {
            EasytrieveStatement candidate = statements.get(i);
            if (candidate.depth() == depth && candidate.is("DO")) {
                return Optional.of(candidate);
            }
            if (candidate.depth() < depth) {
                break;
            }
        }
        return Optional.empty();
    }

    /** A name as the program would display it: the member, or the step of an in-stream program. */
    static String nameOf(EasytrieveProgram program) {
        return program.host().map(host -> host.jobName() + " の " + host.stepName()
                + " のストリーム内プログラム").orElse(program.name());
    }
}

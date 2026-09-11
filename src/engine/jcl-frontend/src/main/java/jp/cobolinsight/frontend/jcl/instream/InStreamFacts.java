package jp.cobolinsight.frontend.jcl.instream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.jcl.JclUtilityFacts;
import jp.cobolinsight.core.jcl.JclUtilityFacts.BindRequest;
import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetAccess;
import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetUse;
import jp.cobolinsight.core.jcl.JclUtilityFacts.ProgramRun;
import jp.cobolinsight.core.jcl.JclUtilityFacts.TableUse;

/**
 * Reads the control cards of every step of a job.
 *
 * <p>The program the step runs chooses the interpreter, and the program is the EXEC target after
 * the PROC expansion has settled it. A TSO launcher is the exception: the launcher is the target
 * and the program that does the work stands on a {@code RUN PROGRAM} card of SYSTSIN, so which
 * interpreter reads SYSIN is decided only once SYSTSIN has been read.
 */
public final class InStreamFacts {

    private InStreamFacts() {
    }

    /** The steps with what their own cards state written onto each of them. */
    public static List<JclStep> readCards(List<JclStep> steps) {
        List<JclStep> out = new ArrayList<>();
        for (JclStep step : steps) {
            if (step.execKind() != JclExecKind.PGM) {
                // A PROC-invoking step runs no program of its own; its expanded steps do.
                out.add(step);
                continue;
            }
            JclUtilityFacts facts = factsOf(step);
            out.add(new JclStep(step.name(), step.execKind(), step.target(), step.condition(),
                    step.ddStatements(), step.parameters(), step.parm(), step.procStepName(),
                    facts.isEmpty() ? Optional.empty() : Optional.of(facts), step.position()));
        }
        return out;
    }

    private static JclUtilityFacts factsOf(JclStep step) {
        String program = step.target().toUpperCase(Locale.ROOT);
        List<ProgramRun> runs = new ArrayList<>();
        List<BindRequest> binds = new ArrayList<>();
        List<DatasetUse> datasetUses = new ArrayList<>();
        List<TableUse> tableUses = new ArrayList<>();
        Map<String, DatasetAccess> ddRoles = new LinkedHashMap<>();

        if (TsoCards.isLauncher(program)) {
            TsoCards.read(UtilityDdRoles.cards(step, TsoCards.CONTROL_DD), runs, binds,
                    datasetUses, ddRoles);
            for (ProgramRun run : runs) {
                if (SqlCards.isSqlProcessor(run.program())) {
                    SqlCards.read(UtilityDdRoles.cards(step, SqlCards.CONTROL_DD), tableUses);
                    break;
                }
            }
        } else if (Dsnutilb.PROGRAM.equals(program)) {
            Dsnutilb.read(UtilityDdRoles.cards(step, Dsnutilb.CONTROL_DD), tableUses);
        } else if (SortCards.isSort(program)) {
            SortCards.read(program,
                    UtilityDdRoles.cards(step, SortCards.controlDd(program)), ddRoles);
        } else if (Idcams.PROGRAM.equals(program)) {
            Idcams.read(UtilityDdRoles.cards(step, Idcams.CONTROL_DD), datasetUses, ddRoles);
        } else if (Iebcopy.PROGRAM.equals(program)) {
            Iebcopy.read(UtilityDdRoles.cards(step, Iebcopy.CONTROL_DD), ddRoles);
        } else if (Iefbr14.PROGRAM.equals(program)) {
            Iefbr14.read(step, ddRoles);
        }
        // The cards had the first word on the DD statements they name; the table and the DISP of
        // each remaining DD say what the step does with the rest.
        UtilityDdRoles.apply(program, step, ddRoles);
        return new JclUtilityFacts(runs, binds, datasetUses, tableUses, ddRoles);
    }
}

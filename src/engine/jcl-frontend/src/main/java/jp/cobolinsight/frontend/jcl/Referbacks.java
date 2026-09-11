package jp.cobolinsight.frontend.jcl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import jp.cobolinsight.core.jcl.JclDataset;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclReferbackMiss;
import jp.cobolinsight.core.jcl.JclStep;

/**
 * Resolves the referbacks of a finished job against its own steps.
 *
 * <p>A referback names a DD written earlier in the job: {@code *.dd} in the same step,
 * {@code *.step.dd} in an earlier one or in this one named by its own name, and
 * {@code *.step.procstep.dd} a DD of a PROC that step called, which the expansion has already
 * flattened into a step named {@code step.procstep}.
 * {@code UNIT=AFF=dd} names a DD of the same step without the asterisk. The DD statements the job
 * owns rather than a step — JOBLIB and SYSCHK — stand before every step, so they resolve among
 * themselves and nothing else resolves against them.
 *
 * <p>A DSN referback takes the data set of the DD it names, and keeps its own text so a reader can
 * still see what was written. Resolution reads the DD statements it has already resolved, so a
 * referback naming a DD that is itself a referback follows the chain to the data set at the end of
 * it. Every other referback is left as written; resolving it only says whether the DD it names is
 * there. One that is not is reported to the caller and the DD keeps what the source gave it.
 */
final class Referbacks {

    /** The job-level DD statements and the steps, each with its referbacks resolved. */
    record Resolved(List<JclDdStatement> jobLevel, List<JclStep> steps) {
    }

    private Referbacks() {
    }

    /** Resolves every DSN referback of a job, and tells {@code misses} of each one it cannot. */
    static Resolved resolve(List<JclDdStatement> jobLevel, List<JclStep> steps,
            List<JclReferbackMiss> misses) {
        List<JclDdStatement> resolvedJobLevel = resolveGroup(jobLevel, "", List.of(), misses);
        if (steps.stream().flatMap(step -> step.ddStatements().stream())
                .allMatch(dd -> dd.referbacks().isEmpty())) {
            return new Resolved(resolvedJobLevel, steps);
        }
        List<JclStep> out = new ArrayList<>();
        for (JclStep step : steps) {
            out.add(new JclStep(step.name(), step.execKind(), step.target(), step.condition(),
                    resolveGroup(step.ddStatements(), step.name(), out, misses), step.parameters(),
                    step.parm(), step.procStepName(), step.utility(), step.position()));
        }
        return new Resolved(resolvedJobLevel, out);
    }

    /**
     * One step's DD statements, each resolved against the DD statements written before it in that
     * step and against the steps already resolved. {@code stepName} is the name of the step they
     * belong to, so a referback that names that step by its own name is resolved there too.
     */
    private static List<JclDdStatement> resolveGroup(List<JclDdStatement> dds, String stepName,
            List<JclStep> earlier, List<JclReferbackMiss> misses) {
        List<JclDdStatement> out = new ArrayList<>();
        for (JclDdStatement dd : dds) {
            out.add(resolve(dd, stepName, earlier, out, misses));
        }
        return out;
    }

    private static JclDdStatement resolve(JclDdStatement dd, String stepName, List<JclStep> steps,
            List<JclDdStatement> earlier, List<JclReferbackMiss> misses) {
        if (dd.referbacks().isEmpty()) {
            return dd;
        }
        Optional<JclDataset> dataset = dd.dataset();
        for (Map.Entry<String, String> referback : dd.referbacks().entrySet()) {
            JclDdStatement target = target(referback.getValue(), stepName, steps, earlier);
            if (target == null) {
                misses.add(new JclReferbackMiss(dd.position().line(), referback.getValue()));
                continue;
            }
            if (isDsn(referback.getKey()) && target.dataset().isPresent()) {
                JclDataset named = target.dataset().orElseThrow();
                dataset = Optional.of(new JclDataset(named.name(), named.member(),
                        named.gdgRelative(), named.temporary(),
                        Optional.of(referback.getValue())));
            }
        }
        return new JclDdStatement(dd.ddName(), dd.datasetName(), dataset, dd.dispositionText(),
                dd.disposition(), dd.sysout(), dd.dummy(), dd.parameters(), dd.referbacks(),
                dd.inStreamData(), dd.concatIndex(), dd.position());
    }

    /** The DD a referback names, or null when nothing written before it carries one. */
    private static JclDdStatement target(String text, String thisStep, List<JclStep> steps,
            List<JclDdStatement> earlier) {
        if (!text.startsWith(JclParameters.REFERBACK_PREFIX)) {
            // UNIT=AFF names a DD of this very step.
            return named(earlier, text);
        }
        String[] parts = text.substring(JclParameters.REFERBACK_PREFIX.length()).split("\\.", -1);
        if (parts.length == 1) {
            return named(earlier, parts[0]);
        }
        if (parts.length > 3) {
            return null;
        }
        String stepName = String.join(".", Arrays.copyOfRange(parts, 0, parts.length - 1));
        String ddName = parts[parts.length - 1];
        // A step may name itself, which the reference allows and jobs written by hand do use; the
        // DD statements of the step itself are the ones written before this one.
        if (stepName.equalsIgnoreCase(thisStep)) {
            JclDdStatement own = named(earlier, ddName);
            if (own != null) {
                return own;
            }
        }
        for (JclStep step : steps) {
            if (step.name().equalsIgnoreCase(stepName)) {
                JclDdStatement found = named(step.ddStatements(), ddName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JclDdStatement named(List<JclDdStatement> dds, String ddName) {
        for (JclDdStatement dd : dds) {
            if (dd.ddName().equalsIgnoreCase(ddName) && dd.concatIndex() == 0) {
                return dd;
            }
        }
        return null;
    }

    private static boolean isDsn(String keyword) {
        String name = keyword.toUpperCase(Locale.ROOT);
        return name.equals("DSN") || name.equals("DSNAME");
    }
}

package jp.cobolinsight.core.jcl;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.source.SourcePosition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * JCL job structure model. Holds the structure after PROC expansion and symbolic resolution,
 * and serves as input to the linker. condition is the textual representation of the job-level COND clause.
 *
 * <p>{@code diagnostics} are the statements of this job the parser could not read and the
 * scheduler directives it read past; the steps around them are in the model all the same.
 * {@code members} names every PROC or INCLUDE member expanded into the job, {@code jcllib} the
 * JCLLIB ORDER datasets as written (recorded, not searched), and {@code schedulerVariables} the
 * scheduler tokens ({@code %%NAME}, {@code $ONAME}) the source carries.
 *
 * <p>{@code parameters} holds the JOB card's own keywords in the order they were written, with the
 * accounting information under {@code ACCOUNT} and the programmer name under {@code PROGRAMMER}.
 * {@code joblib} carries the JOBLIB DD and its concatenation and {@code syschk} the checkpoint data
 * set a {@code RESTART=} rerun reads, both of them DD statements of the job rather than of a step;
 * {@code jes2Cards} holds each {@code /*} card verbatim, {@code outputStatements} each OUTPUT
 * statement's parameters by its name, and {@code unresolvedSymbols} the symbols nothing in the job
 * or its members gave a value to. {@code unresolvedOverrides}, {@code unresolvedReferbacks} and
 * {@code missingMembers} are what the expansion could not place or could not find; they carry no
 * diagnostic of their own, and a rule reports them.
 *
 * <p>{@code position} is where the job itself stands: the JOB card, or the first statement of the
 * file for a member that has no JOB card of its own. A finding about the job as a whole points there
 * rather than at the first line of the file.
 */
public record JclJobModel(String jobName, String sourceFile, Optional<String> condition,
        List<JclStep> steps, List<Finding> diagnostics, List<String> members,
        List<String> jcllib, List<String> schedulerVariables, Map<String, String> parameters,
        List<JclDdStatement> joblib, Optional<JclDdStatement> syschk, List<String> jes2Cards,
        List<String> unresolvedSymbols, Map<String, Map<String, String>> outputStatements,
        List<JclOverrideMiss> unresolvedOverrides, List<JclReferbackMiss> unresolvedReferbacks,
        List<JclMemberMiss> missingMembers, SourcePosition position) {

    public JclJobModel {
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("jobName must not be blank");
        }
        if (sourceFile == null || sourceFile.isBlank()) {
            throw new IllegalArgumentException("sourceFile must not be blank");
        }
        Objects.requireNonNull(condition, "condition");
        steps = List.copyOf(steps);
        diagnostics = List.copyOf(diagnostics);
        members = List.copyOf(members);
        jcllib = List.copyOf(jcllib);
        schedulerVariables = List.copyOf(schedulerVariables);
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        joblib = List.copyOf(joblib);
        Objects.requireNonNull(syschk, "syschk");
        jes2Cards = List.copyOf(jes2Cards);
        unresolvedSymbols = List.copyOf(unresolvedSymbols);
        outputStatements = Collections.unmodifiableMap(new LinkedHashMap<>(outputStatements));
        unresolvedOverrides = List.copyOf(unresolvedOverrides);
        unresolvedReferbacks = List.copyOf(unresolvedReferbacks);
        missingMembers = List.copyOf(missingMembers);
        Objects.requireNonNull(position, "position");
    }

    /** A job with nothing but its steps: what a caller builds when only the flow matters. */
    public JclJobModel(String jobName, String sourceFile, Optional<String> condition,
            List<JclStep> steps) {
        this(jobName, sourceFile, condition, steps, List.of(), List.of(), List.of(), List.of());
    }

    /** A job as the parser built it before it read the JOB card's own parameters. */
    public JclJobModel(String jobName, String sourceFile, Optional<String> condition,
            List<JclStep> steps, List<Finding> diagnostics, List<String> members,
            List<String> jcllib, List<String> schedulerVariables) {
        this(jobName, sourceFile, condition, steps, diagnostics, members, jcllib,
                schedulerVariables, Map.of(), List.of(), Optional.empty(), List.of(), List.of(),
                Map.of(), List.of(), List.of());
    }

    /**
     * A job whose caller knows nothing of the members it names or of where its JOB card stands. The
     * position is the first line of the job's own file, which is where a finding about the job goes
     * when nobody read the JOB card.
     */
    public JclJobModel(String jobName, String sourceFile, Optional<String> condition,
            List<JclStep> steps, List<Finding> diagnostics, List<String> members,
            List<String> jcllib, List<String> schedulerVariables, Map<String, String> parameters,
            List<JclDdStatement> joblib, Optional<JclDdStatement> syschk, List<String> jes2Cards,
            List<String> unresolvedSymbols, Map<String, Map<String, String>> outputStatements,
            List<JclOverrideMiss> unresolvedOverrides,
            List<JclReferbackMiss> unresolvedReferbacks) {
        this(jobName, sourceFile, condition, steps, diagnostics, members, jcllib,
                schedulerVariables, parameters, joblib, syschk, jes2Cards, unresolvedSymbols,
                outputStatements, unresolvedOverrides, unresolvedReferbacks, List.of(),
                SourcePosition.fileStart(sourceFile));
    }
}

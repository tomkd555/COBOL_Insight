package jp.cobolinsight.app.persistence.model;

/**
 * One row of the JCL_STEP table (an EXEC statement of a job). {@code seq} is a 1-based number
 * running over the steps of the whole file, jobs included, and is what puts them in execution
 * order; {@code procStep} is the name the step carries inside the PROC it was expanded from, and
 * is null for a step the job writes itself. {@code file} is the file the statement stands in,
 * which is the PROC member for an expanded step. {@code detailJson} holds the EXEC parameters,
 * the PARM, the COND and what the step's control cards said.
 */
public record JclStepRecord(long id, long sourceId, String jobName, int seq, String stepName,
        String execKind, String target, String procStep, Integer line, String file,
        String detailJson) {

    public JclStepRecord {
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("jobName must not be blank");
        }
        if (stepName == null || stepName.isBlank()) {
            throw new IllegalArgumentException("stepName must not be blank");
        }
        if (execKind == null || execKind.isBlank()) {
            throw new IllegalArgumentException("execKind must not be blank");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("file must not be blank");
        }
        if (detailJson == null) {
            throw new IllegalArgumentException("detailJson must not be null");
        }
    }
}

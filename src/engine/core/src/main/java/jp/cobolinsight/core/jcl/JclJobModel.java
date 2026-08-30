package jp.cobolinsight.core.jcl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JCL job structure model. Holds the structure after PROC expansion and symbolic resolution,
 * and serves as input to the linker. condition is the textual representation of the job-level COND clause.
 */
public record JclJobModel(String jobName, String sourceFile, Optional<String> condition,
        List<JclStep> steps) {

    public JclJobModel {
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("jobName must not be blank");
        }
        if (sourceFile == null || sourceFile.isBlank()) {
            throw new IllegalArgumentException("sourceFile must not be blank");
        }
        Objects.requireNonNull(condition, "condition");
        steps = List.copyOf(steps);
    }
}

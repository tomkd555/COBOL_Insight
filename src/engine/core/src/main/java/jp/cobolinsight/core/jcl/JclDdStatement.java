package jp.cobolinsight.core.jcl;

import jp.cobolinsight.core.source.SourcePosition;

import java.util.Objects;
import java.util.Optional;

/** A DD statement. Allows forms without a dataset name (e.g. SYSOUT=). */
public record JclDdStatement(String ddName, Optional<String> datasetName, SourcePosition position) {

    public JclDdStatement {
        if (ddName == null || ddName.isBlank()) {
            throw new IllegalArgumentException("ddName must not be blank");
        }
        Objects.requireNonNull(datasetName, "datasetName");
        Objects.requireNonNull(position, "position");
    }
}

package jp.cobolinsight.engineapi.jcl;

import jp.cobolinsight.engineapi.source.SourcePosition;

import java.util.Objects;
import java.util.Optional;

/** DD文。データセット名を持たない形式(SYSOUT=など)を許す。 */
public record JclDdStatement(String ddName, Optional<String> datasetName, SourcePosition position) {

    public JclDdStatement {
        if (ddName == null || ddName.isBlank()) {
            throw new IllegalArgumentException("ddName must not be blank");
        }
        Objects.requireNonNull(datasetName, "datasetName");
        Objects.requireNonNull(position, "position");
    }
}

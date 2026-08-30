package jp.cobolinsight.core.sql;

import java.util.Objects;
import java.util.Optional;

/**
 * A reversible host variable mangling mapping. Maps the original data name (with hyphens)
 * bidirectionally to its mangled name. Optionally holds an indicator variable (the ind in
 * :host:ind).
 */
public record HostVariableBinding(String originalName, String mangledName,
        Optional<String> indicatorName) {

    public HostVariableBinding {
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("originalName must not be blank");
        }
        if (mangledName == null || mangledName.isBlank()) {
            throw new IllegalArgumentException("mangledName must not be blank");
        }
        Objects.requireNonNull(indicatorName, "indicatorName");
    }
}

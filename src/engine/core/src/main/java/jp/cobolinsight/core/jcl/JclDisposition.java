package jp.cobolinsight.core.jcl;

import java.util.Objects;
import java.util.Optional;

/**
 * The DISP parameter read apart. {@code status} is the first subparameter, {@code NEW} when the
 * author left it out ({@code DISP=(,PASS)}), and {@code normal} and {@code abnormal} the second and
 * third. {@code raw} is the parameter as written, parentheses included, which is what a message
 * quotes back.
 */
public record JclDisposition(String status, Optional<String> normal, Optional<String> abnormal,
        String raw) {

    /** What JCL assumes when the status subparameter is left out. */
    public static final String DEFAULT_STATUS = "NEW";

    public JclDisposition {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(abnormal, "abnormal");
        Objects.requireNonNull(raw, "raw");
    }
}

package jp.cobolinsight.core.jcl;

import java.util.Locale;
import java.util.Set;

/**
 * A member the job names and the member library does not hold: the PROC an EXEC calls, or the member
 * an INCLUDE brings in. {@code line} is the line the statement stands on, {@code kind} says which of
 * the two named it, and {@code name} is the member name as the statement writes it, symbols
 * resolved.
 *
 * <p>The expansion carries on without the member, so the job model holds the call step or the
 * INCLUDE and nothing of what the member would have contributed. That is a gap in what the model can
 * say about the job rather than an error in the job, and a rule reports it.
 */
public record JclMemberMiss(int line, String kind, String name) {

    /** A PROC an EXEC statement calls. */
    public static final String PROC = "PROC";

    /** A member an INCLUDE statement brings in. */
    public static final String INCLUDE = "INCLUDE";

    private static final Set<String> KINDS = Set.of(PROC, INCLUDE);

    public JclMemberMiss {
        if (kind == null || !KINDS.contains(kind.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("kind must be PROC or INCLUDE, was " + kind);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        kind = kind.toUpperCase(Locale.ROOT);
    }
}

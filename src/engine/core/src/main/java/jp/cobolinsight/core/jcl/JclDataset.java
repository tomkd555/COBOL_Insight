package jp.cobolinsight.core.jcl;

import java.util.Objects;
import java.util.Optional;

/**
 * The DSN parameter read apart. {@code name} is the data set name alone: {@code A.B.C(MEM)} leaves
 * {@code A.B.C} with {@code MEM} as the member, {@code A.B.GDG(+1)} leaves {@code A.B.GDG} with
 * {@code +1} as the relative generation, and {@code &&NAME} is a temporary data set of the job.
 *
 * <p>{@code referback} carries the {@code *.step.dd} text a DSN was written as; {@code name} is
 * then the data set the referenced DD names, or the referback text itself while the reference is
 * unresolved.
 */
public record JclDataset(String name, Optional<String> member, Optional<Integer> gdgRelative,
        boolean temporary, Optional<String> referback) {

    public JclDataset {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(gdgRelative, "gdgRelative");
        Objects.requireNonNull(referback, "referback");
    }

    /** A plain data set name: no member, no generation, not temporary and no referback. */
    public JclDataset(String name) {
        this(name, Optional.empty(), Optional.empty(), false, Optional.empty());
    }
}

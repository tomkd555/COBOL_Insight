package jp.cobolinsight.core.jcl;

import jp.cobolinsight.core.source.SourcePosition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A DD statement. Allows forms without a dataset name (e.g. SYSOUT=).
 *
 * <p>{@code parameters} holds every keyword of the statement in the order it was written, the
 * keyword up to the first {@code =} against the text after it, with symbols resolved; a parameter
 * with no value at all ({@code DUMMY}, {@code *}, {@code DATA}) maps to the empty string. The
 * fields around it are that map read: {@code datasetName} and {@code dispositionText} as written,
 * {@code dataset} and {@code disposition} taken apart, {@code sysout} the whole SYSOUT operand as
 * written — {@code (A,,STD)} as readily as a class on its own — and {@code dummy} whether the DD
 * names no data set at all, which {@code DUMMY} and {@code DSN=NULLFILE} both say.
 *
 * <p>{@code referbacks} names each parameter written as a referback ({@code DSN=*.STEP1.DD1},
 * {@code UNIT=AFF=DD5}) against what it points at, {@code inStreamData} carries the lines of a
 * {@code DD *} or {@code DD DATA} stream verbatim without their delimiter, and
 * {@code concatIndex} is 0 for the named DD and 1 upwards for each nameless statement concatenated
 * under it, every entry of a concatenation carrying the named DD's own name.
 */
public record JclDdStatement(String ddName, Optional<String> datasetName,
        Optional<JclDataset> dataset, Optional<String> dispositionText,
        Optional<JclDisposition> disposition, Optional<String> sysout, boolean dummy,
        Map<String, String> parameters, Map<String, String> referbacks, List<String> inStreamData,
        int concatIndex, SourcePosition position) {

    public JclDdStatement {
        if (ddName == null || ddName.isBlank()) {
            throw new IllegalArgumentException("ddName must not be blank");
        }
        Objects.requireNonNull(datasetName, "datasetName");
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(dispositionText, "dispositionText");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(sysout, "sysout");
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        referbacks = Collections.unmodifiableMap(new LinkedHashMap<>(referbacks));
        inStreamData = List.copyOf(inStreamData);
        if (concatIndex < 0) {
            throw new IllegalArgumentException("concatIndex must not be negative");
        }
        Objects.requireNonNull(position, "position");
    }

    /** A DD with nothing but its name, data set and DISP: what a caller builds by hand. */
    public JclDdStatement(String ddName, Optional<String> datasetName,
            Optional<String> dispositionText, SourcePosition position) {
        this(ddName, datasetName, datasetName.filter(n -> !n.isBlank()).map(JclDataset::new),
                dispositionText,
                Optional.empty(), Optional.empty(), false, Map.of(), Map.of(), List.of(), 0,
                position);
    }
}

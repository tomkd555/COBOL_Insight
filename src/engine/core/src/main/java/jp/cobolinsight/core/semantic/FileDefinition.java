package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourcePosition;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * One FILE-CONTROL entry of a program, with what the PROCEDURE DIVISION does with it.
 *
 * @param fileName     the name the SELECT gives the file, which the FD and every statement use
 * @param ddName       the DD name the ASSIGN clause points at, uppercased: the last qualifier of
 *                     a name written in the system form ({@code UT-S-INFILE},
 *                     {@code SYS010-UT-INFILE}), or a literal whole, hyphens and all, because the
 *                     literal is what the system reads. Empty when the clause names no DD, which
 *                     includes a clause naming only a device class ({@code ASSIGN TO DISK})
 * @param organisation the ORGANIZATION clause as written, empty when the entry states none
 * @param accesses     the modes the program opens the file in, in the order the enum declares
 * @param position     where the SELECT entry stands
 */
public record FileDefinition(String fileName, Optional<String> ddName,
        Optional<String> organisation, Set<FileAccess> accesses, SourcePosition position) {

    public FileDefinition {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        Objects.requireNonNull(ddName, "ddName");
        Objects.requireNonNull(organisation, "organisation");
        accesses = Collections.unmodifiableSortedSet(new TreeSet<>(accesses));
        Objects.requireNonNull(position, "position");
    }
}

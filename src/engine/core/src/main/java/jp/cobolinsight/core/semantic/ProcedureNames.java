package jp.cobolinsight.core.semantic;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Which procedure a name written in a PERFORM, THRU, GO TO, ALTER or HANDLE means, and which
 * procedures a PERFORM runs ({@link #rangeOf}). The control flow graph, the rules, the transpiler
 * and the stored paragraph flow, its THRU paragraph included, take names and ranges from here, so
 * they agree.
 *
 * <ul>
 *   <li>A name qualified by a section ({@code P1 OF SECT-B}, {@code P1 IN SECT-B}) means the
 *       procedure of that name in that section.</li>
 *   <li>An unqualified name means the procedure of that name in the section the statement stands
 *       in, and otherwise the first procedure defined with that name.</li>
 * </ul>
 *
 * <p>A qualifier naming a section that holds no such procedure is read as if it were absent. A
 * section counts as lying in itself, so inside section X the name X means section X.
 */
public final class ProcedureNames {

    private final List<Procedure> procedures;
    private final Map<String, Integer> firstByName = new HashMap<>();
    private final Map<String, Integer> firstBySectionAndName = new HashMap<>();

    public ProcedureNames(List<Procedure> procedures) {
        this.procedures = List.copyOf(procedures);
        for (int i = 0; i < procedures.size(); i++) {
            Procedure procedure = procedures.get(i);
            firstByName.putIfAbsent(upper(procedure.name()), i);
            firstBySectionAndName.putIfAbsent(key(sectionOf(procedure), procedure.name()), i);
        }
    }

    /** The section a procedure lies in: its own name for a section, empty outside any section. */
    public static Optional<String> sectionOf(Procedure procedure) {
        return procedure.kind() == ProcedureKind.SECTION ? Optional.of(procedure.name())
                : procedure.sectionName();
    }

    /**
     * The index in definition order of the procedure {@code name} means, written with
     * {@code qualifier} in a statement of section {@code fromSection}; -1 when no procedure
     * carries the name.
     */
    public int indexOf(String name, Optional<String> qualifier, Optional<String> fromSection) {
        if (qualifier.isPresent()) {
            Integer qualified = firstBySectionAndName.get(key(qualifier, name));
            if (qualified != null) {
                return qualified;
            }
        }
        Integer inSection = firstBySectionAndName.get(key(fromSection, name));
        if (inSection != null) {
            return inSection;
        }
        return firstByName.getOrDefault(upper(name), -1);
    }

    /** The procedures a PERFORM runs, by index in definition order: {@code from} to {@code to}. */
    public record Range(int from, int to) {
    }

    /**
     * The range a PERFORM written in section {@code fromSection} runs: from its target to the
     * last procedure of its THRU end, or of its target where it has no THRU. A THRU name no
     * procedure carries, or one that resolves to a procedure ahead of the target, gives the range
     * of the target alone. Empty when the target resolves to no procedure.
     */
    public Optional<Range> rangeOf(PerformRelation perform, Optional<String> fromSection) {
        int from = indexOf(perform.targetProcedure(), perform.targetSection(), fromSection);
        if (from < 0) {
            return Optional.empty();
        }
        int thru = perform.thruProcedure()
                .map(name -> indexOf(name, perform.thruSection(), fromSection))
                .orElse(-1);
        return Optional.of(new Range(from, lastOf(thru >= from ? thru : from)));
    }

    /**
     * The index of the last procedure control runs through when the procedure at {@code index} is
     * performed, or is the THRU end of a PERFORM: a section runs through the paragraphs that follow
     * it in that section, and a paragraph is its own end. -1 stays -1.
     */
    public int lastOf(int index) {
        if (index < 0 || procedures.get(index).kind() != ProcedureKind.SECTION) {
            return index;
        }
        String section = procedures.get(index).name();
        int last = index;
        while (last + 1 < procedures.size()
                && procedures.get(last + 1).kind() == ProcedureKind.PARAGRAPH
                && procedures.get(last + 1).sectionName().map(section::equalsIgnoreCase)
                        .orElse(false)) {
            last++;
        }
        return last;
    }

    private static String key(Optional<String> section, String name) {
        return section.map(ProcedureNames::upper).orElse("") + "\0" + upper(name);
    }

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }
}

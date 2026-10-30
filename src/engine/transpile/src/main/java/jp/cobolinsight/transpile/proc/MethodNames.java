package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.PerformRelation;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.ProcedureMap;
import jp.cobolinsight.core.semantic.ProcedureNames;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.transpile.emit.Identifiers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The method each procedure becomes, and the method a PERFORM reaches.
 *
 * <p>A procedure whose name no other procedure of the program carries becomes the method of its
 * name. Procedures that share a name (COBOL compares names without case) are told apart by their
 * section: {@code COMMON-EXIT} of section {@code A-SECT} becomes {@code COMMON_EXIT_IN_A_SECT}, the
 * way COBOL writes {@code COMMON-EXIT IN A-SECT}. A qualified name that is still taken gets
 * {@code _2}, {@code _3} and so on.
 *
 * <p>A PERFORM reaches the method of the procedure {@link ProcedureNames} resolves for its target,
 * from the section of the procedure that holds the statement and with any IN or OF qualifier, which
 * is the procedure the control flow graph and the stored paragraph flow name as well.
 */
public final class MethodNames {

    private final ProcedureNames names;
    private final ProcedureMap procedureMap;
    private final List<String> methods = new ArrayList<>();
    /** Every method name given out, those of THRU ranges included. */
    private final Set<String> taken = new HashSet<>();

    public MethodNames(CobolSemanticModel model) {
        List<Procedure> procedures = model.procedures();
        names = new ProcedureNames(procedures);
        procedureMap = new ProcedureMap(model);
        Map<String, Integer> count = new HashMap<>();
        for (Procedure procedure : procedures) {
            count.merge(upper(procedure.name()), 1, Integer::sum);
        }
        for (Procedure procedure : procedures) {
            if (count.get(upper(procedure.name())) == 1) {
                taken.add(Identifiers.sanitize(procedure.name()));
            }
        }
        for (Procedure procedure : procedures) {
            String plain = Identifiers.sanitize(procedure.name());
            if (count.get(upper(procedure.name())) == 1) {
                methods.add(plain);
                continue;
            }
            String qualified = ProcedureNames.sectionOf(procedure)
                    .map(section -> Identifiers.sanitize(procedure.name() + "-IN-" + section))
                    .orElse(plain);
            String method = qualified;
            for (int n = 2; !taken.add(method); n++) {
                method = qualified + "_" + n;
            }
            methods.add(method);
        }
    }

    /** The method of the procedure at {@code index} of the model's procedures. */
    String of(int index) {
        return methods.get(index);
    }

    /**
     * The methods of every procedure a PERFORM statement runs, in written order: the range
     * {@link ProcedureNames#rangeOf} gives, from the target to the last procedure of its THRU
     * end, a performed section's paragraphs included. A method runs its own procedure's
     * statements and returns, so the range is called procedure by procedure. Empty when the model
     * holds no relation for the statement or its target resolves to no procedure.
     */
    Optional<List<String>> performed(SimpleStatement perform) {
        return range(perform).map(range -> {
            List<String> methods = new ArrayList<>();
            for (int i = range.from(); i <= range.to(); i++) {
                methods.add(of(i));
            }
            return List.copyOf(methods);
        });
    }

    /**
     * The range a PERFORM statement runs, as {@link ProcedureNames#rangeOf} gives it; empty when
     * the model holds no relation for the statement or its target resolves to no procedure.
     */
    Optional<ProcedureNames.Range> range(SimpleStatement perform) {
        List<PerformRelation> relations = procedureMap.performsOf(perform);
        if (relations.isEmpty()) {
            return Optional.empty();
        }
        PerformRelation relation = relations.stream()
                .filter(r -> r.range().equals(perform.range())).findFirst()
                .orElse(relations.get(0));
        return names.rangeOf(relation, procedureMap.sectionOf(relation));
    }

    /**
     * A method name for the whole range from the procedure at {@code from} to the one at
     * {@code to}: the two ends joined by {@code _THRU_}, with {@code _2} and on where taken.
     */
    String ofRange(int from, int to) {
        String base = of(from) + "_THRU_" + of(to);
        String method = base;
        for (int n = 2; !taken.add(method); n++) {
            method = base + "_" + n;
        }
        return method;
    }

    private static String upper(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}

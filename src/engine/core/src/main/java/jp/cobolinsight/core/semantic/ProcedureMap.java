package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourceRange;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Where the statements and the PERFORM relations of a model stand: the procedure that holds each
 * statement, and the statement and procedure that write each PERFORM relation. Statements are
 * told apart by identity, so the two statements a COPY member included twice gives the same range
 * stay two.
 *
 * <p>A relation belongs to the first simple statement, in source order, whose range holds the
 * relation's range and whose procedure carries the relation's {@code fromProcedure}: a PERFORM
 * statement holds its own relation, a SORT or MERGE its INPUT and OUTPUT PROCEDURE, and a
 * statement with an AT END, INVALID KEY, ON EXCEPTION or similar phrase the PERFORM written in
 * it. The control flow graph, the stored paragraph flow and the rules all read it from here.
 *
 * <p>A CALL relation and an EXEC CICS or EXEC SQL block belong the same way to the first simple
 * statement whose range holds theirs: the CALL or EXEC statement itself, or the statement in whose
 * phrase they are written.
 */
public final class ProcedureMap {

    private final Map<Statement, Integer> procedureByStatement = new IdentityHashMap<>();
    private final Map<Statement, List<PerformRelation>> performsByStatement =
            new IdentityHashMap<>();
    private final Map<PerformRelation, Integer> procedureByPerform = new IdentityHashMap<>();
    private final List<List<PerformRelation>> performsByProcedure = new ArrayList<>();
    private final Map<Statement, List<CallRelation>> callsByStatement = new IdentityHashMap<>();
    private final Map<Statement, List<EmbeddedBlock>> blocksByStatement = new IdentityHashMap<>();
    private final List<Procedure> procedures;

    public ProcedureMap(CobolSemanticModel model) {
        List<PerformRelation> performs = model.performs();
        boolean[] claimed = new boolean[performs.size()];
        boolean[] claimedCalls = new boolean[model.calls().size()];
        boolean[] claimedBlocks = new boolean[model.embeddedBlocks().size()];
        procedures = model.procedures();
        for (int i = 0; i < procedures.size(); i++) {
            Procedure procedure = procedures.get(i);
            List<PerformRelation> inProcedure = new ArrayList<>();
            performsByProcedure.add(inProcedure);
            int index = i;
            Statements.walk(procedure.statements(), statement -> {
                procedureByStatement.put(statement, index);
                if (!(statement instanceof SimpleStatement simple)) {
                    return;
                }
                for (PerformRelation perform : claim(simple, performs,
                        perform -> perform.fromProcedure().equalsIgnoreCase(procedure.name()),
                        PerformRelation::range, claimed, performsByStatement)) {
                    procedureByPerform.put(perform, index);
                    inProcedure.add(perform);
                }
                claim(simple, model.calls(), call -> true, CallRelation::range, claimedCalls,
                        callsByStatement);
                claim(simple, model.embeddedBlocks(), block -> true, EmbeddedBlock::range,
                        claimedBlocks, blocksByStatement);
            });
        }
    }

    /**
     * Gives the statement the unclaimed items {@code accepts} lets it take whose range its own
     * holds. A statement takes each distinct item once: a COPY member included twice gives two
     * equal items, one for each of the two statements it brings in, while the INPUT and OUTPUT
     * PROCEDURE of one SORT are two items of one statement.
     */
    private static <T> List<T> claim(SimpleStatement simple, List<T> items,
            Predicate<T> accepts, Function<T, SourceRange> rangeOf, boolean[] claimed,
            Map<Statement, List<T>> into) {
        List<T> taken = new ArrayList<>();
        Set<T> distinct = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            if (!claimed[i] && accepts.test(item) && simple.range().contains(rangeOf.apply(item))
                    && distinct.add(item)) {
                claimed[i] = true;
                taken.add(item);
            }
        }
        if (!taken.isEmpty()) {
            into.put(simple, taken);
        }
        return taken;
    }

    /** The CALL relations a statement writes, itself or in a phrase, in model order. */
    public List<CallRelation> callsOf(Statement statement) {
        return callsByStatement.getOrDefault(statement, List.of());
    }

    /** The EXEC CICS and EXEC SQL blocks a statement is or writes in a phrase, in model order. */
    public List<EmbeddedBlock> blocksOf(Statement statement) {
        return blocksByStatement.getOrDefault(statement, List.of());
    }

    /** The PERFORM relations a statement writes, in model order; empty for none. */
    public List<PerformRelation> performsOf(Statement statement) {
        return performsByStatement.getOrDefault(statement, List.of());
    }

    /** The PERFORM relations the statements of the procedure at {@code index} write. */
    public List<PerformRelation> performsIn(int index) {
        return performsByProcedure.get(index);
    }

    /** The index of the procedure that holds the statement, nested ones included; -1 for none. */
    public int procedureOf(Statement statement) {
        return procedureByStatement.getOrDefault(statement, -1);
    }

    /** The index of the procedure whose statement writes the relation; -1 for none. */
    public int procedureOf(PerformRelation perform) {
        return procedureByPerform.getOrDefault(perform, -1);
    }

    /**
     * The section the statement that writes the relation stands in, which an unqualified name
     * in it is resolved from; empty when no procedure's statement writes it or it stands outside
     * any section.
     */
    public Optional<String> sectionOf(PerformRelation perform) {
        int owner = procedureOf(perform);
        return owner < 0 ? Optional.empty() : ProcedureNames.sectionOf(procedures.get(owner));
    }
}

package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.CopyInlineExpansion;
import jp.cobolinsight.core.source.ExpandedCopyLine;
import jp.cobolinsight.core.source.SourcePosition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The lines of a program's own source file that its procedures and statements stand on. A
 * procedure or a statement a COPY member brings in carries the copybook's own lines in the model;
 * here it is given the line of the COPY statement in the program that brought it in, and with
 * nested COPY that is the outermost one, the COPY statement the program file itself writes.
 *
 * <p>The model does not say which COPY statement a position came from, so it is read from the
 * COPY expansions the parse already built: each names the line of a COPY statement of the program
 * file and the copybook lines it spliced there, those of nested copybooks included. Procedures and
 * statements are walked in the order the program writes them, and a copied position takes the
 * first COPY statement at or after the last line the walk reached whose expansion holds that very
 * copybook line and has yet to supply that position: an inclusion supplies each position of its
 * copybook once, so the second inclusion of a copybook supplies the second statement standing at
 * one of its positions, whatever program statements stand between them. A statement looks only
 * at the COPY statements between its own procedure's line and the next procedure's: that is the
 * inclusion inside the paragraph that owns it, when one copybook is copied into several
 * paragraphs.
 *
 * <p>A procedure the program file starts keeps the end line Che4z gives it, unless the last
 * statement in it came from a COPY member: the end then stands in the copybook's coordinates, so
 * the end becomes the furthest line its own statements reach in the program file.
 */
public final class ProgramLines {

    /**
     * One COPY statement of the program file, and the copybook lines its expansion holds, the
     * lines of copybooks nested COPY statements bring in included.
     */
    private record Inclusion(int line, Set<CopybookLine> copybookLines) {
    }

    private record CopybookLine(String copybook, int line) {
    }

    private final String programFile;
    private final List<Inclusion> inclusions = new ArrayList<>();
    private final int[] starts;
    private final int[] ends;
    private final Map<Statement, Integer> copiedStatementLines = new IdentityHashMap<>();

    /** Program line of the last position the walk reached. */
    private int cursor;
    /** For each inclusion, by index, the copied positions it has supplied. */
    private final List<Set<SourcePosition>> supplied = new ArrayList<>();

    public ProgramLines(CobolSemanticModel model) {
        programFile = model.sourceFile();
        for (CopyInlineExpansion expansion : model.copyInlineExpansions()) {
            Set<CopybookLine> lines = new HashSet<>();
            for (ExpandedCopyLine line : expansion.lines()) {
                lines.add(new CopybookLine(line.copybookPath().orElse(expansion.copybookPath()),
                        line.copybookLine()));
            }
            inclusions.add(new Inclusion(expansion.copyStatementLine(), lines));
        }
        inclusions.sort(Comparator.comparingInt(Inclusion::line));
        inclusions.forEach(inclusion -> supplied.add(new HashSet<>()));

        List<Procedure> procedures = model.procedures();
        starts = new int[procedures.size()];
        ends = new int[procedures.size()];
        boolean[] copiedEnd = new boolean[procedures.size()];
        restart(0);
        for (int i = 0; i < procedures.size(); i++) {
            starts[i] = lineOf(procedures.get(i).range().start(), Integer.MAX_VALUE);
        }
        for (int i = 0; i < procedures.size(); i++) {
            Procedure procedure = procedures.get(i);
            int upper = i + 1 < procedures.size() ? starts[i + 1] : Integer.MAX_VALUE;
            restart(starts[i]);
            int[] furthest = {starts[i]};
            boolean[] lastCopied = {false};
            Statements.walk(procedure.statements(), statement -> {
                SourcePosition start = statement.range().start();
                if (isProgramFile(start)) {
                    restart(start.line());
                    if (!(statement instanceof CompoundStatement)) {
                        furthest[0] = Math.max(furthest[0], statement.range().end().line());
                        lastCopied[0] = false;
                    }
                } else {
                    int line = lineOf(start, upper);
                    copiedStatementLines.put(statement, line);
                    furthest[0] = Math.max(furthest[0], line);
                    if (!(statement instanceof CompoundStatement)) {
                        lastCopied[0] = true;
                    }
                }
            });
            if (!isProgramFile(procedure.range().start())) {
                ends[i] = starts[i];
                copiedEnd[i] = true;
            } else if (lastCopied[0]) {
                ends[i] = furthest[0];
                copiedEnd[i] = true;
            } else {
                ends[i] = procedure.range().end().line();
            }
        }
        // A section ends where its last paragraph does, so it inherits a copied end from it.
        ProcedureNames names = new ProcedureNames(procedures);
        for (int i = 0; i < procedures.size(); i++) {
            if (procedures.get(i).kind() != ProcedureKind.SECTION
                    || !isProgramFile(procedures.get(i).range().start())) {
                continue;
            }
            int last = names.lastOf(i);
            if (last > i && copiedEnd[last]) {
                ends[i] = Math.max(starts[i], ends[last]);
            }
        }
    }

    /** The program line a procedure starts on. */
    public int startOf(int procedure) {
        return starts[procedure];
    }

    /** The program line a procedure ends on. */
    public int endOf(int procedure) {
        return ends[procedure];
    }

    /**
     * The program line of the COPY statement that brought a statement in, or null for a statement
     * the program file itself writes.
     */
    public Integer copyLineOf(Statement statement) {
        return copiedStatementLines.get(statement);
    }

    private boolean isProgramFile(SourcePosition position) {
        return programFile.equals(position.file());
    }

    private void restart(int line) {
        cursor = line;
    }

    /**
     * The program line of a position, moving the walk on to it. A copied position takes the first
     * COPY statement at or after the cursor and no further down than {@code upper} whose
     * expansion holds its line and has yet to supply it; where none does, it stays on the last
     * line the walk reached.
     */
    private int lineOf(SourcePosition position, int upper) {
        if (isProgramFile(position)) {
            restart(position.line());
            return cursor;
        }
        CopybookLine copied = new CopybookLine(position.file(), position.line());
        for (int i = 0; i < inclusions.size(); i++) {
            Inclusion inclusion = inclusions.get(i);
            if (inclusion.line() < cursor) {
                continue;
            }
            if (inclusion.line() > upper) {
                break;
            }
            if (inclusion.copybookLines().contains(copied) && supplied.get(i).add(position)) {
                cursor = inclusion.line();
                break;
            }
        }
        return cursor;
    }

}

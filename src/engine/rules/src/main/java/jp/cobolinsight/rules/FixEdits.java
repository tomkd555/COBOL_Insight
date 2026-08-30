package jp.cobolinsight.rules;

import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.fix.FixedFormatNormalizer;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Edit-building helpers shared by {@code FixProducer} implementations. Because Finding.location
 * pins the column to 1 and collapses byteOffset to unknown, this class re-resolves the target
 * statement's full {@link SourceRange} from the semantic model and builds the insertion edit.
 *
 * <p>Column wrapping of the inserted statement is done using UTF-8-relative byte lengths
 * ({@link #LAYOUT_CHARSET}). Wrapping occurs sooner for encodings with wider byte lengths, so a
 * physical line laid out under UTF-8 still does not exceed column 72 when written back in a
 * shorter encoding such as Shift_JIS. The sources under samples that fix suggestions target are
 * all UTF-8, so the columns match exactly.
 */
public final class FixEdits {

    /** The encoding used to compute column wrapping for inserted lines. Re-encoding at apply time is done by the fix module using the original encoding. */
    public static final Charset LAYOUT_CHARSET = StandardCharsets.UTF_8;

    private static final FixedFormatNormalizer NORMALIZER = new FixedFormatNormalizer();

    private FixEdits() {
    }

    /** Looks up the semantic model matching sourceFile. */
    public static Optional<CobolSemanticModel> modelOf(AnalysisContext context, String sourceFile) {
        return context.cobolPrograms().stream()
                .filter(model -> model.sourceFile().equals(sourceFile))
                .findFirst();
    }

    /**
     * Returns the first simple statement whose start line is {@code line} and that matches the
     * predicate, searching the procedure division down through its nesting. Because
     * Finding.location's column is lost, the target statement is identified by start line and
     * predicate instead.
     */
    public static Optional<SimpleStatement> findSimpleStatement(CobolSemanticModel model, int line,
            Predicate<SimpleStatement> predicate) {
        for (Procedure procedure : model.procedures()) {
            Optional<SimpleStatement> found = find(procedure.statements(), line, predicate);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<SimpleStatement> find(List<Statement> statements, int line,
            Predicate<SimpleStatement> predicate) {
        for (Statement statement : statements) {
            if (statement instanceof SimpleStatement simple) {
                if (simple.range().start().line() == line && predicate.test(simple)) {
                    return Optional.of(simple);
                }
            } else if (statement instanceof CompoundStatement compound) {
                for (StatementBlock block : compound.blocks()) {
                    Optional<SimpleStatement> found = find(block.statements(), line, predicate);
                    if (found.isPresent()) {
                        return found;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** One or more physical lines laying out the target statement into fixed-format Area B. */
    public static List<String> layout(String statement) {
        return NORMALIZER.layoutStatement(statement, LAYOUT_CHARSET);
    }

    /**
     * Determines whether the target statement's ending physical line (1-based) closes the
     * statement with a fixed-format terminating period. If it does, inserting a new
     * period-terminated statement right after it does not break the enclosing structure (IF,
     * PERFORM, a paragraph, etc.). Inserting a period-terminated statement right after a statement
     * that is not closed (one in the middle of a block) would prematurely terminate the enclosing
     * statement and break the syntax, so insertion-style fix generation is limited to cases this
     * check judges safe.
     */
    public static boolean endsSentence(String sourceText, int line) {
        String[] lines = sourceText.split("\n", -1);
        if (line < 1 || line > lines.length) {
            return false;
        }
        return lines[line - 1].stripTrailing().endsWith(".");
    }

    /**
     * An edit that inserts a new statement right after the target range (at the start of the line
     * following the ending line). To avoid splitting the identification field (columns 73-80) of
     * that same physical line, the insertion is placed at the empty range at the start of the next
     * line rather than at the ending column. The replacement joins the laid-out physical lines
     * with newlines and appends a trailing newline.
     */
    public static TextEdit insertStatementAfter(SourceRange target, String statement) {
        String file = target.end().file();
        int nextLine = target.end().line() + 1;
        String replacement = String.join("\n", layout(statement)) + "\n";
        SourcePosition at = new SourcePosition(file, nextLine, 1, SourcePosition.UNKNOWN_BYTE_OFFSET);
        return new TextEdit(new SourceRange(at, at), replacement);
    }
}

package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.source.CopyExpansionEntry;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Common support for SQL-finding rules: the reported location of a statement, the statements that
 * belong to one program, and the CFG node a statement runs as.
 */
final class SqlAdviceSupport {

    /** The verb the COBOL frontend gives an EXEC SQL block's statement. */
    private static final String EXEC_SQL = "EXEC SQL";

    private SqlAdviceSupport() {
    }

    /** Uses the SQL statement's start line as the reported location. Column is fixed at 1, matching other rules. */
    static SourcePosition location(SqlStatementModel statement) {
        SourcePosition start = statement.range().start();
        return new SourcePosition(start.file(), start.line(), 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
    }

    /**
     * The table the statement names, as a noun phrase a message can be built on: the name and a
     * trailing space where there is one, and the bare word for a table otherwise, so no message
     * carries a stray space.
     */
    static String tableOf(SqlStatementModel statement) {
        return statement.referencedTables().isEmpty() ? "表"
                : statement.referencedTables().get(0) + " ";
    }

    /** The same, said as the rows of that table. */
    static String rowsOf(SqlStatementModel statement) {
        return statement.referencedTables().isEmpty() ? "該当する行"
                : statement.referencedTables().get(0) + " に該当する行";
    }

    /**
     * The statements of {@code all} that stand in the program's own file. A statement pulled in
     * from a copybook carries the copybook's path and is left out: it has no node of this
     * program's CFG to be placed on. The context holds every program's statements in one list,
     * which is why the caller passes it in.
     */
    static List<SqlStatementModel> statementsOf(CobolSemanticModel model,
            List<SqlStatementModel> all) {
        List<SqlStatementModel> mine = new ArrayList<>();
        for (SqlStatementModel statement : all) {
            if (statement.range().start().file().equals(model.sourceFile())) {
                mine.add(statement);
            }
        }
        return mine;
    }

    /**
     * The statements of {@code all} that stand in a copybook the program expands. What a cursor
     * declared in a copybook says still decides how the program's own OPEN and FETCH behave, and
     * {@link #statementsOf} leaves the declaration out because it has no node of this program's
     * CFG. Only the copybooks this program expands answer, so a cursor of the same name in another
     * program's copybook does not.
     */
    static List<SqlStatementModel> copiedStatements(CobolSemanticModel model,
            List<SqlStatementModel> all) {
        Set<String> copybooks = new LinkedHashSet<>();
        for (CopyExpansionEntry entry : model.copyExpansions()) {
            copybooks.add(entry.copybookPath());
        }
        List<SqlStatementModel> copied = new ArrayList<>();
        for (SqlStatementModel statement : all) {
            if (copybooks.contains(statement.range().start().file())) {
                copied.add(statement);
            }
        }
        return copied;
    }

    /**
     * The CFG node whose statement covers the SQL statement's range: the EXEC SQL node the
     * frontend built for the same block. Empty when the graph carries no such node, which is what
     * a statement inside a copybook or outside the PROCEDURE DIVISION leaves.
     */
    static Optional<CfgNode> cfgNodeOf(ControlFlowGraph cfg, SqlStatementModel statement) {
        SourceRange range = statement.range();
        for (CfgNode node : cfg.nodes()) {
            Statement carried = node.statement().orElse(null);
            if (carried instanceof SimpleStatement simple && EXEC_SQL.equals(simple.verb())
                    && covers(simple.range(), range)) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    private static boolean covers(SourceRange outer, SourceRange inner) {
        return outer.start().file().equals(inner.start().file())
                && outer.start().line() <= inner.start().line()
                && inner.end().line() <= outer.end().line();
    }
}

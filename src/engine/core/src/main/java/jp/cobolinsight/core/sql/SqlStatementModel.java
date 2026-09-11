package jp.cobolinsight.core.sql;

import jp.cobolinsight.core.source.SourceRange;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The parse result of an embedded SQL statement. Holds both the original text (originalText) and
 * the mangled text (mangledText), so rules can report using the original data names. range is
 * the position in the original COBOL source. structureSignals holds the syntax-level structural
 * signals that SQL findings (S001, S002, S004) read; the components after it are the facts the
 * frontend reads off the parse tree, so a rule does not have to parse the text again.
 *
 * <p>analysis says whether the grammar accepted the statement. A DEGRADED statement carries what
 * a keyword scan and a text read recover: kind, referencedTables, tableAccess, cursorName,
 * positionedCursor, dynamic, whenever and the rowsetSize or rowsetHostVariable of a multi-row
 * INSERT. Its mangledText may be empty, its structureSignals are always empty and every other fact
 * is empty; diagnostic then says what stopped the parse. A reader of hasWhere, hasOrderBy, withHold
 * or forUpdate has to check {@link #isFullyAnalysed()} first: a keyword scan reads no clause, so
 * all four stay false whether the statement carries the clause or not.</p>
 *
 * @param kind               the statement kind
 * @param originalText       the EXEC SQL text exactly as the source spells it
 * @param mangledText        the text the grammar was handed, host variables replaced by tokens
 * @param hostVariables      the host variable bindings, in order of appearance
 * @param referencedTables   every table the statement names, in order of first appearance
 * @param range              the position of the statement in the original source
 * @param structureSignals   the structural signals the SQL advice rules read
 * @param analysis           FULL when the grammar accepted the statement, DEGRADED otherwise
 * @param diagnostic         what stopped the full analysis; empty for FULL
 * @param cursorName         the cursor a DECLARE, OPEN, FETCH, CLOSE, ALLOCATE or positioned
 *                           statement names
 * @param positionedCursor   the cursor of a WHERE CURRENT OF
 * @param intoTargets        the original data names of the INTO list, in source order
 * @param tableAccess        table as written, to the letters R (read), C (create), U (update) and
 *                           D (delete) it is accessed with, in that order; a dynamic statement
 *                           books the single entry from {@code ?} to {@code ?}
 * @param cteNames           the names the statement's WITH clause defines, in order; a column
 *                           attributed to one of them comes from the query rather than a table
 * @param columnRefs         every column reference of the statement
 * @param selectList         the items of the outermost select list as text, {@code *} included
 * @param setPairs           the assignments of an UPDATE or of the UPDATE branch of a MERGE
 * @param insertColumns      the column list of an INSERT; empty when the statement writes none
 * @param insertValues       the VALUES items of an INSERT as text; empty for an INSERT ... SELECT
 * @param declaredTable      the table a DECLARE TABLE or a CREATE TABLE declares, as written
 * @param declaredColumns    the columns of a DECLARE TABLE or a CREATE TABLE
 * @param whenever           the condition and target of a WHENEVER
 * @param includeMember      the member an INCLUDE names
 * @param procedureName      the procedure a CALL names
 * @param statementName      the SQL statement name a PREPARE, EXECUTE, DESCRIBE, DECLARE ...
 *                           STATEMENT or dynamic cursor names
 * @param withHold           whether a cursor is declared WITH HOLD
 * @param forUpdate          whether a FOR UPDATE clause is present
 * @param forUpdateColumns   the columns of FOR UPDATE OF; empty when there is no OF
 * @param hasWhere           whether the statement narrows its rows, WHERE CURRENT OF included
 * @param hasOrderBy         whether an ORDER BY clause is present
 * @param dynamic            whether the statement runs SQL text built at run time
 * @param isolation          the isolation level of a WITH UR, CS, RS or RR clause
 * @param rowsetSize         the row count of a multi-row FETCH or INSERT written as a literal
 * @param rowsetHostVariable the host variable that gives that row count instead
 */
public record SqlStatementModel(SqlStatementKind kind, String originalText, String mangledText,
        List<HostVariableBinding> hostVariables, List<String> referencedTables, SourceRange range,
        SqlStructureSignals structureSignals, SqlAnalysis analysis, Optional<String> diagnostic,
        Optional<String> cursorName, Optional<String> positionedCursor, List<String> intoTargets,
        Map<String, String> tableAccess, List<String> cteNames, List<SqlColumnRef> columnRefs,
        List<String> selectList,
        List<SqlSetPair> setPairs, List<String> insertColumns, List<String> insertValues,
        Optional<String> declaredTable, List<SqlDeclaredColumn> declaredColumns,
        Optional<WheneverClause.Clause> whenever,
        Optional<String> includeMember, Optional<String> procedureName,
        Optional<String> statementName, boolean withHold, boolean forUpdate,
        List<String> forUpdateColumns, boolean hasWhere, boolean hasOrderBy, boolean dynamic,
        Optional<String> isolation, Optional<Integer> rowsetSize,
        Optional<String> rowsetHostVariable) {

    public SqlStatementModel {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(analysis, "analysis");
        Objects.requireNonNull(diagnostic, "diagnostic");
        if (originalText == null || originalText.isBlank()) {
            throw new IllegalArgumentException("originalText must not be blank");
        }
        if (mangledText == null) {
            throw new IllegalArgumentException("mangledText must not be null");
        }
        if (mangledText.isBlank() && analysis == SqlAnalysis.FULL) {
            throw new IllegalArgumentException("mangledText must not be blank");
        }
        hostVariables = List.copyOf(hostVariables);
        referencedTables = List.copyOf(referencedTables);
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(structureSignals, "structureSignals");
        Objects.requireNonNull(cursorName, "cursorName");
        Objects.requireNonNull(positionedCursor, "positionedCursor");
        intoTargets = List.copyOf(intoTargets);
        // The order of first appearance reaches the reader, so the map is wrapped rather than
        // copied through Map.copyOf, whose iteration order is its own; the null check Map.copyOf
        // would have done is done here instead.
        Map<String, String> access = new LinkedHashMap<>();
        tableAccess.forEach((table, letters) -> access.put(
                Objects.requireNonNull(table, "tableAccess table"),
                Objects.requireNonNull(letters, "tableAccess letters")));
        tableAccess = Collections.unmodifiableMap(access);
        cteNames = List.copyOf(cteNames);
        columnRefs = List.copyOf(columnRefs);
        selectList = List.copyOf(selectList);
        setPairs = List.copyOf(setPairs);
        insertColumns = List.copyOf(insertColumns);
        insertValues = List.copyOf(insertValues);
        Objects.requireNonNull(declaredTable, "declaredTable");
        declaredColumns = List.copyOf(declaredColumns);
        Objects.requireNonNull(whenever, "whenever");
        Objects.requireNonNull(includeMember, "includeMember");
        Objects.requireNonNull(procedureName, "procedureName");
        Objects.requireNonNull(statementName, "statementName");
        forUpdateColumns = List.copyOf(forUpdateColumns);
        Objects.requireNonNull(isolation, "isolation");
        Objects.requireNonNull(rowsetSize, "rowsetSize");
        Objects.requireNonNull(rowsetHostVariable, "rowsetHostVariable");
    }

    /** A statement with no extracted facts, the shape every caller built before the facts existed. */
    public SqlStatementModel(SqlStatementKind kind, String originalText, String mangledText,
            List<HostVariableBinding> hostVariables, List<String> referencedTables,
            SourceRange range, SqlStructureSignals structureSignals, SqlAnalysis analysis,
            Optional<String> diagnostic) {
        this(kind, originalText, mangledText, hostVariables, referencedTables, range,
                structureSignals, analysis, diagnostic, Optional.empty(), Optional.empty(),
                List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false, false, List.of(), false, false, false,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** A fully analysed statement, the shape every caller built before DEGRADED existed. */
    public SqlStatementModel(SqlStatementKind kind, String originalText, String mangledText,
            List<HostVariableBinding> hostVariables, List<String> referencedTables,
            SourceRange range, SqlStructureSignals structureSignals) {
        this(kind, originalText, mangledText, hostVariables, referencedTables, range,
                structureSignals, SqlAnalysis.FULL, Optional.empty());
    }

    /** Whether the grammar accepted the statement, so every field can be trusted. */
    public boolean isFullyAnalysed() {
        return analysis == SqlAnalysis.FULL;
    }

    /**
     * Collects the kind, the tables and the facts of one statement while the frontend walks its
     * parse tree, and builds the model once the caller can supply the text and the position.
     * Nothing is checked here; the record's own constructor does that.
     */
    public static final class Builder {

        private SqlStatementKind kind = SqlStatementKind.OTHER;
        private List<String> referencedTables = List.of();
        private Optional<String> cursorName = Optional.empty();
        private Optional<String> positionedCursor = Optional.empty();
        private List<String> intoTargets = List.of();
        private Map<String, String> tableAccess = Map.of();
        private List<String> cteNames = List.of();
        private List<SqlColumnRef> columnRefs = List.of();
        private List<String> selectList = List.of();
        private List<SqlSetPair> setPairs = List.of();
        private List<String> insertColumns = List.of();
        private List<String> insertValues = List.of();
        private Optional<String> declaredTable = Optional.empty();
        private List<SqlDeclaredColumn> declaredColumns = List.of();
        private Optional<WheneverClause.Clause> whenever = Optional.empty();
        private Optional<String> includeMember = Optional.empty();
        private Optional<String> procedureName = Optional.empty();
        private Optional<String> statementName = Optional.empty();
        private boolean withHold;
        private boolean forUpdate;
        private List<String> forUpdateColumns = List.of();
        private boolean hasWhere;
        private boolean hasOrderBy;
        private boolean dynamic;
        private Optional<String> isolation = Optional.empty();
        private Optional<Integer> rowsetSize = Optional.empty();
        private Optional<String> rowsetHostVariable = Optional.empty();

        public Builder kind(SqlStatementKind value) {
            this.kind = value;
            return this;
        }

        public Builder referencedTables(List<String> value) {
            this.referencedTables = value;
            return this;
        }

        public Builder cursorName(String value) {
            this.cursorName = Optional.ofNullable(value);
            return this;
        }

        public Builder positionedCursor(String value) {
            this.positionedCursor = Optional.ofNullable(value);
            return this;
        }

        public Builder intoTargets(List<String> value) {
            this.intoTargets = value;
            return this;
        }

        public Builder tableAccess(Map<String, String> value) {
            this.tableAccess = value;
            return this;
        }

        public Builder cteNames(List<String> value) {
            this.cteNames = value;
            return this;
        }

        public Builder columnRefs(List<SqlColumnRef> value) {
            this.columnRefs = value;
            return this;
        }

        public Builder selectList(List<String> value) {
            this.selectList = value;
            return this;
        }

        public Builder setPairs(List<SqlSetPair> value) {
            this.setPairs = value;
            return this;
        }

        public Builder insertColumns(List<String> value) {
            this.insertColumns = value;
            return this;
        }

        public Builder insertValues(List<String> value) {
            this.insertValues = value;
            return this;
        }

        public Builder declaredTable(String value) {
            this.declaredTable = Optional.ofNullable(value);
            return this;
        }

        public Builder declaredColumns(List<SqlDeclaredColumn> value) {
            this.declaredColumns = value;
            return this;
        }

        public Builder whenever(Optional<WheneverClause.Clause> value) {
            this.whenever = value;
            return this;
        }

        public Builder includeMember(String value) {
            this.includeMember = Optional.ofNullable(value);
            return this;
        }

        public Builder procedureName(String value) {
            this.procedureName = Optional.ofNullable(value);
            return this;
        }

        public Builder statementName(String value) {
            this.statementName = Optional.ofNullable(value);
            return this;
        }

        public Builder withHold(boolean value) {
            this.withHold = value;
            return this;
        }

        public Builder forUpdate(boolean value, List<String> columns) {
            this.forUpdate = value;
            this.forUpdateColumns = columns;
            return this;
        }

        public Builder hasWhere(boolean value) {
            this.hasWhere = value;
            return this;
        }

        public Builder hasOrderBy(boolean value) {
            this.hasOrderBy = value;
            return this;
        }

        public Builder dynamic(boolean value) {
            this.dynamic = value;
            return this;
        }

        public Builder isolation(String value) {
            this.isolation = Optional.ofNullable(value);
            return this;
        }

        public Builder rowsetSize(Integer value) {
            this.rowsetSize = Optional.ofNullable(value);
            return this;
        }

        public Builder rowsetHostVariable(String value) {
            this.rowsetHostVariable = Optional.ofNullable(value);
            return this;
        }

        public SqlStatementKind kind() {
            return kind;
        }

        public List<String> referencedTables() {
            return referencedTables;
        }

        public Optional<String> cursorName() {
            return cursorName;
        }

        public Optional<String> positionedCursor() {
            return positionedCursor;
        }

        public Map<String, String> tableAccess() {
            return tableAccess;
        }

        public List<String> intoTargets() {
            return intoTargets;
        }

        public Optional<Integer> rowsetSize() {
            return rowsetSize;
        }

        /** The model, once the caller supplies the text, the bindings and the position. */
        public SqlStatementModel build(String originalText, String mangledText,
                List<HostVariableBinding> hostVariables, SourceRange range,
                SqlStructureSignals structureSignals, SqlAnalysis analysis,
                Optional<String> diagnostic) {
            return new SqlStatementModel(kind, originalText, mangledText, hostVariables,
                    referencedTables, range, structureSignals, analysis, diagnostic, cursorName,
                    positionedCursor, intoTargets, tableAccess, cteNames, columnRefs, selectList,
                    setPairs,
                    insertColumns, insertValues, declaredTable, declaredColumns, whenever,
                    includeMember,
                    procedureName, statementName, withHold, forUpdate, forUpdateColumns, hasWhere,
                    hasOrderBy, dynamic, isolation, rowsetSize, rowsetHostVariable);
        }
    }
}

package jp.cobolinsight.app.persistence;

import jp.cobolinsight.app.persistence.model.CallEdgeRecord;
import jp.cobolinsight.app.persistence.model.FindingRecord;
import jp.cobolinsight.app.persistence.model.JclDdRecord;
import jp.cobolinsight.app.persistence.model.JclStepRecord;
import jp.cobolinsight.app.persistence.model.LineMapRecord;
import jp.cobolinsight.app.persistence.model.NodeRecord;
import jp.cobolinsight.app.persistence.model.ParagraphEdgeRecord;
import jp.cobolinsight.app.persistence.model.ParagraphRecord;
import jp.cobolinsight.app.persistence.model.ProgramRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import jp.cobolinsight.app.persistence.model.SqlColumnUseRecord;
import jp.cobolinsight.app.persistence.model.SqlStatementRecord;
import jp.cobolinsight.app.persistence.model.SqlTableUseRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Inserts and fetches for every table, call-graph reachability queries, and full per-source deletion. */
public final class PersistenceDao {

    private final Connection connection;

    public PersistenceDao(Connection connection) {
        this.connection = connection;
    }

    // ---- SOURCE ----

    public void insertSource(SourceRecord source) {
        update("INSERT INTO SOURCE(id, root, path, codepage, content_hash, byte_size) "
                        + "VALUES (?,?,?,?,?,?)",
                source.id(), source.root(), source.path(), source.codepage(), source.contentHash(),
                source.byteSize());
    }

    public Optional<SourceRecord> findSource(long id) {
        return queryOne(SELECT_SOURCE + " WHERE id = ?", PersistenceDao::mapSource, id);
    }

    public Optional<SourceRecord> findSourceByPath(String root, String path) {
        return queryOne(SELECT_SOURCE + " WHERE root = ? AND path = ?", PersistenceDao::mapSource,
                root, path);
    }

    /** Returns only the rows imported from the given asset folder. Rows from other asset folders are excluded. */
    public List<SourceRecord> findSourcesByRoot(String root) {
        return queryList(SELECT_SOURCE + " WHERE root = ? ORDER BY id", PersistenceDao::mapSource,
                root);
    }

    /** The maximum SOURCE.id across all asset folders. Used as the starting point for numbering new rows. 0 if none exist. */
    public long maxSourceId() {
        return queryOne("SELECT COALESCE(MAX(id), 0) AS max_id FROM SOURCE",
                rs -> rs.getLong("max_id")).orElse(0L);
    }

    /**
     * Deletes the given source's row together with the child-table rows that reference it. Child-row
     * deletion relies on the DDL's ON DELETE CASCADE, so it only cascades on a connection with foreign
     * key constraints enabled.
     */
    public void deleteSourceCascade(long sourceId) {
        update("DELETE FROM SOURCE WHERE id = ?", sourceId);
    }

    public List<SourceRecord> findAllSources() {
        return queryList(SELECT_SOURCE + " ORDER BY id", PersistenceDao::mapSource);
    }

    private static final String SELECT_SOURCE =
            "SELECT id, root, path, codepage, content_hash, byte_size FROM SOURCE";

    private static SourceRecord mapSource(ResultSet rs) throws SQLException {
        return new SourceRecord(rs.getLong("id"), rs.getString("root"), rs.getString("path"),
                rs.getString("codepage"), rs.getString("content_hash"), rs.getLong("byte_size"));
    }

    // ---- PROGRAM / PARAGRAPH ----

    public void insertProgram(ProgramRecord program) {
        update("INSERT INTO PROGRAM(id, source_id, program_id_name) VALUES (?,?,?)",
                program.id(), program.sourceId(), program.programIdName());
    }

    public Optional<ProgramRecord> findProgram(long id) {
        return queryOne("SELECT id, source_id, program_id_name FROM PROGRAM WHERE id = ?",
                rs -> new ProgramRecord(rs.getLong("id"), rs.getLong("source_id"),
                        rs.getString("program_id_name")),
                id);
    }

    public void insertParagraph(ParagraphRecord paragraph) {
        update("INSERT INTO PARAGRAPH(id, program_id, name, start_line, end_line) VALUES (?,?,?,?,?)",
                paragraph.id(), paragraph.programId(), paragraph.name(), paragraph.startLine(),
                paragraph.endLine());
    }

    public Optional<ParagraphRecord> findParagraph(long id) {
        return queryOne("SELECT id, program_id, name, start_line, end_line FROM PARAGRAPH WHERE id = ?",
                rs -> new ParagraphRecord(rs.getLong("id"), rs.getLong("program_id"), rs.getString("name"),
                        rs.getInt("start_line"), rs.getInt("end_line")),
                id);
    }

    // ---- PARAGRAPH_EDGE ----

    public void insertParagraphEdge(ParagraphEdgeRecord edge) {
        update("INSERT INTO PARAGRAPH_EDGE(id, program_source_id, from_paragraph, to_paragraph, "
                        + "to_name, kind, line, seq) VALUES (?,?,?,?,?,?,?,?)",
                edge.id(), edge.programSourceId(), edge.fromParagraph(), edge.toParagraph(),
                edge.toName(), edge.kind(), edge.line(), edge.seq());
    }

    /** Returns the flow between the given program's paragraphs, ordered by edge ID (= paragraph definition order / outgoing-edge order). */
    public List<ParagraphEdgeRecord> findParagraphEdgesByProgram(long programSourceId) {
        return queryList(SELECT_PARAGRAPH_EDGE + " WHERE program_source_id = ? ORDER BY id",
                PersistenceDao::mapParagraphEdge, programSourceId);
    }

    private static final String SELECT_PARAGRAPH_EDGE =
            "SELECT id, program_source_id, from_paragraph, to_paragraph, to_name, kind, line, seq "
                    + "FROM PARAGRAPH_EDGE";

    private static ParagraphEdgeRecord mapParagraphEdge(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        long programSourceId = rs.getLong("program_source_id");
        long fromParagraph = rs.getLong("from_paragraph");
        long toParagraph = rs.getLong("to_paragraph");
        Long to = rs.wasNull() ? null : toParagraph;
        String toName = rs.getString("to_name");
        String kind = rs.getString("kind");
        int line = rs.getInt("line");
        Integer lineOrNull = rs.wasNull() ? null : line;
        return new ParagraphEdgeRecord(id, programSourceId, fromParagraph, to, toName, kind,
                lineOrNull, rs.getInt("seq"));
    }

    // ---- NODE / CALL_EDGE ----

    public void insertNode(NodeRecord node) {
        update("INSERT INTO NODE(id, type, label) VALUES (?,?,?)", node.id(), node.type(), node.label());
    }

    public Optional<NodeRecord> findNode(long id) {
        return queryOne("SELECT id, type, label FROM NODE WHERE id = ?",
                rs -> new NodeRecord(rs.getLong("id"), rs.getString("type"), rs.getString("label")), id);
    }

    public List<NodeRecord> findAllNodes() {
        return queryList("SELECT id, type, label FROM NODE ORDER BY id",
                rs -> new NodeRecord(rs.getLong("id"), rs.getString("type"), rs.getString("label")));
    }

    public void updateNode(NodeRecord node) {
        update("UPDATE NODE SET type = ?, label = ? WHERE id = ?", node.type(), node.label(),
                node.id());
    }

    public void deleteNode(long nodeId) {
        update("DELETE FROM NODE WHERE id = ?", nodeId);
    }

    public void insertCallEdge(CallEdgeRecord edge) {
        update("INSERT INTO CALL_EDGE(id, from_node, to_node, kind, resolution, host_var, seq, "
                        + "line, access, attrs_json) VALUES (?,?,?,?,?,?,?,?,?,?)",
                edge.id(), edge.fromNode(), edge.toNode(), edge.kind(), edge.resolution(),
                edge.hostVar(), edge.seq(), edge.line(), edge.access(), edge.attrsJson());
    }

    public Optional<CallEdgeRecord> findCallEdge(long id) {
        return queryOne(SELECT_CALL_EDGE + " WHERE id = ?", PersistenceDao::mapCallEdge, id);
    }

    public List<CallEdgeRecord> findAllCallEdges() {
        return queryList(SELECT_CALL_EDGE + " ORDER BY id", PersistenceDao::mapCallEdge);
    }

    /** Outgoing edges of the given node. Ordered by edge ID; the original order is carried by each edge's seq. */
    public List<CallEdgeRecord> findEdgesFrom(long nodeId) {
        return queryList(SELECT_CALL_EDGE + " WHERE from_node = ? ORDER BY id",
                PersistenceDao::mapCallEdge, nodeId);
    }

    public List<CallEdgeRecord> findEdgesTo(long nodeId) {
        return queryList(SELECT_CALL_EDGE + " WHERE to_node = ? ORDER BY id",
                PersistenceDao::mapCallEdge, nodeId);
    }

    private static final String SELECT_CALL_EDGE =
            "SELECT id, from_node, to_node, kind, resolution, host_var, seq, line, access, "
                    + "attrs_json FROM CALL_EDGE";

    public void deleteCallEdgesFrom(long nodeId, String kind) {
        update("DELETE FROM CALL_EDGE WHERE from_node = ? AND kind = ?", nodeId, kind);
    }

    public void deleteCallEdgesTo(long nodeId, String kind) {
        update("DELETE FROM CALL_EDGE WHERE to_node = ? AND kind = ?", nodeId, kind);
    }

    // For NODE, CALL_EDGE and FINDING, rows written by per-source analysis and rows written by
    // call-graph construction are separated by an ID floor. The former are numbered starting from
    // SOURCE.id, the latter are numbered at or above a floor set by the caller.
    // Deleting everything at or above the floor and reinserting it lets the graph be rebuilt on its own
    // while leaving the per-source rows intact.

    /** Bulk-deletes nodes with ID at or above the given value. Used to replace nodes with no matching source (job steps, datasets, etc.). */
    public void deleteNodesIdAtLeast(long idFloor) {
        update("DELETE FROM NODE WHERE id >= ?", idFloor);
    }

    /** Bulk-deletes edges with ID at or above the given value. Used to replace call-graph edges. */
    public void deleteCallEdgesIdAtLeast(long idFloor) {
        update("DELETE FROM CALL_EDGE WHERE id >= ?", idFloor);
    }

    /** Bulk-deletes findings with ID at or above the given value. Used to replace findings produced by call-graph construction. */
    public void deleteFindingsIdAtLeast(long idFloor) {
        update("DELETE FROM FINDING WHERE id >= ?", idFloor);
    }

    /** Uses a recursive CTE to return the set of node IDs reachable from the given node (the start node itself is included only when there is a cycle). */
    public Set<Long> reachableFrom(long nodeId) {
        String sql = """
                WITH RECURSIVE reachable(id) AS (
                    SELECT to_node FROM CALL_EDGE WHERE from_node = ?
                    UNION
                    SELECT ce.to_node FROM CALL_EDGE ce JOIN reachable r ON ce.from_node = r.id
                )
                SELECT id FROM reachable
                """;
        List<Long> ids = queryList(sql, rs -> rs.getLong("id"), nodeId);
        return new LinkedHashSet<>(ids);
    }

    private static CallEdgeRecord mapCallEdge(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        long fromNode = rs.getLong("from_node");
        long toNode = rs.getLong("to_node");
        String kind = rs.getString("kind");
        String resolution = rs.getString("resolution");
        String hostVar = rs.getString("host_var");
        int seq = rs.getInt("seq");
        int line = rs.getInt("line");
        Integer lineOrNull = rs.wasNull() ? null : line;
        return new CallEdgeRecord(id, fromNode, toNode, kind, resolution, hostVar, seq,
                lineOrNull, rs.getString("access"), rs.getString("attrs_json"));
    }

    // ---- FINDING ----

    public void insertFinding(FindingRecord finding) {
        update("INSERT INTO FINDING(id, rule_id, level, source_id, start_line, start_col, message) "
                        + "VALUES (?,?,?,?,?,?,?)",
                finding.id(), finding.ruleId(), finding.level(), finding.sourceId(), finding.startLine(),
                finding.startCol(), finding.message());
    }

    public Optional<FindingRecord> findFinding(long id) {
        return queryOne("SELECT id, rule_id, level, source_id, start_line, start_col, message "
                + "FROM FINDING WHERE id = ?", PersistenceDao::mapFinding, id);
    }

    public List<FindingRecord> findFindingsBySource(long sourceId) {
        return queryList("SELECT id, rule_id, level, source_id, start_line, start_col, message "
                + "FROM FINDING WHERE source_id = ?", PersistenceDao::mapFinding,
                sourceId);
    }

    private static FindingRecord mapFinding(ResultSet rs) throws SQLException {
        return new FindingRecord(rs.getLong("id"), rs.getString("rule_id"), rs.getString("level"),
                rs.getLong("source_id"), rs.getInt("start_line"), rs.getInt("start_col"),
                rs.getString("message"));
    }

    // ---- JCL_STEP / JCL_DD ----

    public void insertJclStep(JclStepRecord step) {
        update("INSERT INTO JCL_STEP(id, source_id, job_name, seq, step_name, exec_kind, target, "
                        + "proc_step, line, file, detail_json) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                step.id(), step.sourceId(), step.jobName(), step.seq(), step.stepName(),
                step.execKind(), step.target(), step.procStep(), step.line(), step.file(),
                step.detailJson());
    }

    /** The steps of one JCL source, in execution order (which is the order their ids run in). */
    public List<JclStepRecord> findJclStepsBySource(long sourceId) {
        return queryList("SELECT id, source_id, job_name, seq, step_name, exec_kind, target, "
                        + "proc_step, line, file, detail_json FROM JCL_STEP "
                        + "WHERE source_id = ? ORDER BY id",
                rs -> {
                    int line = rs.getInt("line");
                    Integer lineOrNull = rs.wasNull() ? null : line;
                    return new JclStepRecord(rs.getLong("id"), rs.getLong("source_id"),
                            rs.getString("job_name"), rs.getInt("seq"), rs.getString("step_name"),
                            rs.getString("exec_kind"), rs.getString("target"),
                            rs.getString("proc_step"), lineOrNull, rs.getString("file"),
                            rs.getString("detail_json"));
                },
                sourceId);
    }

    public void insertJclDd(JclDdRecord dd) {
        update("INSERT INTO JCL_DD(id, step_id, seq, dd_name, dsn, access, line, file, "
                        + "detail_json) VALUES (?,?,?,?,?,?,?,?,?)",
                dd.id(), dd.stepId(), dd.seq(), dd.ddName(), dd.dsn(), dd.access(), dd.line(),
                dd.file(), dd.detailJson());
    }

    /** The DD statements of one step, in the order the source writes them. */
    public List<JclDdRecord> findJclDdsByStep(long stepId) {
        return queryList("SELECT id, step_id, seq, dd_name, dsn, access, line, file, detail_json "
                        + "FROM JCL_DD WHERE step_id = ? ORDER BY id",
                rs -> {
                    int line = rs.getInt("line");
                    Integer lineOrNull = rs.wasNull() ? null : line;
                    return new JclDdRecord(rs.getLong("id"), rs.getLong("step_id"),
                            rs.getInt("seq"), rs.getString("dd_name"), rs.getString("dsn"),
                            rs.getString("access"), lineOrNull, rs.getString("file"),
                            rs.getString("detail_json"));
                },
                stepId);
    }

    // ---- SQL_STMT / SQL_TABLE_USE / SQL_COLUMN_USE ----

    public void insertSqlStatement(SqlStatementRecord statement) {
        update("INSERT INTO SQL_STMT(id, source_id, program_id, seq, kind, cursor_name, line, "
                        + "end_line, analysis, text, file, detail_json) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                statement.id(), statement.sourceId(), statement.programId(), statement.seq(),
                statement.kind(), statement.cursorName(), statement.line(), statement.endLine(),
                statement.analysis(), statement.text(), statement.file(),
                statement.detailJson());
    }

    /** The SQL statements of one source, in the order they appear in it. */
    public List<SqlStatementRecord> findSqlStatementsBySource(long sourceId) {
        return queryList("SELECT id, source_id, program_id, seq, kind, cursor_name, line, "
                        + "end_line, analysis, text, file, detail_json FROM SQL_STMT "
                        + "WHERE source_id = ? ORDER BY id",
                rs -> {
                    long programId = rs.getLong("program_id");
                    Long programOrNull = rs.wasNull() ? null : programId;
                    return new SqlStatementRecord(rs.getLong("id"), rs.getLong("source_id"),
                            programOrNull, rs.getInt("seq"), rs.getString("kind"),
                            rs.getString("cursor_name"), rs.getInt("line"), rs.getInt("end_line"),
                            rs.getString("analysis"), rs.getString("text"), rs.getString("file"),
                            rs.getString("detail_json"));
                },
                sourceId);
    }

    public void insertSqlTableUse(SqlTableUseRecord use) {
        update("INSERT INTO SQL_TABLE_USE(id, stmt_id, table_name, access) VALUES (?,?,?,?)",
                use.id(), use.stmtId(), use.tableName(), use.access());
    }

    public List<SqlTableUseRecord> findSqlTableUsesByStatement(long stmtId) {
        return queryList("SELECT id, stmt_id, table_name, access FROM SQL_TABLE_USE "
                        + "WHERE stmt_id = ? ORDER BY id",
                rs -> new SqlTableUseRecord(rs.getLong("id"), rs.getLong("stmt_id"),
                        rs.getString("table_name"), rs.getString("access")),
                stmtId);
    }

    public void insertSqlColumnUse(SqlColumnUseRecord use) {
        update("INSERT INTO SQL_COLUMN_USE(id, stmt_id, table_name, column_name) VALUES (?,?,?,?)",
                use.id(), use.stmtId(), use.tableName(), use.columnName());
    }

    public List<SqlColumnUseRecord> findSqlColumnUsesByStatement(long stmtId) {
        return queryList("SELECT id, stmt_id, table_name, column_name FROM SQL_COLUMN_USE "
                        + "WHERE stmt_id = ? ORDER BY id",
                rs -> new SqlColumnUseRecord(rs.getLong("id"), rs.getLong("stmt_id"),
                        rs.getString("table_name"), rs.getString("column_name")),
                stmtId);
    }

    // ---- LINE_MAP ----

    public void insertLineMap(LineMapRecord lineMap) {
        update("INSERT INTO LINE_MAP(id, cobol_source_id, cobol_line_start, cobol_line_end, gen_file, "
                        + "gen_line_start, gen_line_end, kind, note, anchor_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                lineMap.id(), lineMap.cobolSourceId(), lineMap.cobolLineStart(), lineMap.cobolLineEnd(),
                lineMap.genFile(), lineMap.genLineStart(), lineMap.genLineEnd(), lineMap.kind(),
                lineMap.note(), lineMap.anchorId());
    }

    public Optional<LineMapRecord> findLineMap(long id) {
        return queryOne("SELECT id, cobol_source_id, cobol_line_start, cobol_line_end, gen_file, "
                + "gen_line_start, gen_line_end, kind, note, anchor_id FROM LINE_MAP WHERE id = ?",
                PersistenceDao::mapLineMap, id);
    }

    public List<LineMapRecord> findLineMapsBySource(long cobolSourceId) {
        return queryList("SELECT id, cobol_source_id, cobol_line_start, cobol_line_end, gen_file, "
                + "gen_line_start, gen_line_end, kind, note, anchor_id FROM LINE_MAP "
                + "WHERE cobol_source_id = ?",
                PersistenceDao::mapLineMap, cobolSourceId);
    }

    /** Deletes all line-map rows for the given source (called before writing, to make re-running translate idempotent). */
    public void deleteLineMapsBySource(long cobolSourceId) {
        update("DELETE FROM LINE_MAP WHERE cobol_source_id = ?", cobolSourceId);
    }

    private static LineMapRecord mapLineMap(ResultSet rs) throws SQLException {
        return new LineMapRecord(rs.getLong("id"), rs.getLong("cobol_source_id"),
                rs.getInt("cobol_line_start"), rs.getInt("cobol_line_end"), rs.getString("gen_file"),
                rs.getInt("gen_line_start"), rs.getInt("gen_line_end"), rs.getString("kind"),
                rs.getString("note"), rs.getString("anchor_id"));
    }

    // ---- Transaction ----

    /** Runs the given work as a single transaction, rolling back and rethrowing if a runtime exception occurs. */
    public void inTransaction(Runnable work) {
        try {
            connection.setAutoCommit(false);
            try {
                work.run();
                connection.commit();
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new PersistenceException("transaction control failed", e);
        }
    }

    // ---- JDBC helpers ----

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapper.map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("query failed: " + sql, e);
        }
    }

    private <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapper.map(rs));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new PersistenceException("query failed: " + sql, e);
        }
    }

    private void update(String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("update failed: " + sql, e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}

package jp.cobolinsight.app.persistence;

import jp.cobolinsight.app.persistence.model.BmsFieldRecord;
import jp.cobolinsight.app.persistence.model.BmsMapRecord;
import jp.cobolinsight.app.persistence.model.BmsMapsetRecord;
import jp.cobolinsight.app.persistence.model.CallEdgeRecord;
import jp.cobolinsight.app.persistence.model.EncodingInfoRecord;
import jp.cobolinsight.app.persistence.model.FindingRecord;
import jp.cobolinsight.app.persistence.model.LineMapRecord;
import jp.cobolinsight.app.persistence.model.NodeRecord;
import jp.cobolinsight.app.persistence.model.ParagraphEdgeRecord;
import jp.cobolinsight.app.persistence.model.ParagraphRecord;
import jp.cobolinsight.app.persistence.model.ProgramRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import jp.cobolinsight.app.persistence.model.SqlStmtRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Inserts and fetches for all 13 tables, call-graph reachability queries, and full per-source deletion. */
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

    // ---- ENCODING_INFO ----

    public void insertEncodingInfo(EncodingInfoRecord info) {
        update("INSERT INTO ENCODING_INFO(source_id, detected_charset, confidence, manual_override, "
                        + "so_si_present) VALUES (?,?,?,?,?)",
                info.sourceId(), info.detectedCharset(), info.confidence(), info.manualOverride(),
                info.soSiPresent());
    }

    public Optional<EncodingInfoRecord> findEncodingInfo(long sourceId) {
        return queryOne("SELECT source_id, detected_charset, confidence, manual_override, so_si_present "
                        + "FROM ENCODING_INFO WHERE source_id = ?",
                rs -> new EncodingInfoRecord(rs.getLong("source_id"), rs.getString("detected_charset"),
                        rs.getDouble("confidence"), rs.getBoolean("manual_override"),
                        rs.getBoolean("so_si_present")),
                sourceId);
    }

    // ---- BMS_MAPSET / BMS_MAP / BMS_FIELD ----

    public void insertBmsMapset(BmsMapsetRecord mapset) {
        update("INSERT INTO BMS_MAPSET(id, source_id, name) VALUES (?,?,?)",
                mapset.id(), mapset.sourceId(), mapset.name());
    }

    public Optional<BmsMapsetRecord> findBmsMapset(long id) {
        return queryOne("SELECT id, source_id, name FROM BMS_MAPSET WHERE id = ?",
                rs -> new BmsMapsetRecord(rs.getLong("id"), rs.getLong("source_id"), rs.getString("name")),
                id);
    }

    public void insertBmsMap(BmsMapRecord map) {
        update("INSERT INTO BMS_MAP(id, mapset_id, name, size_rows, size_cols) VALUES (?,?,?,?,?)",
                map.id(), map.mapsetId(), map.name(), map.sizeRows(), map.sizeCols());
    }

    public Optional<BmsMapRecord> findBmsMap(long id) {
        return queryOne("SELECT id, mapset_id, name, size_rows, size_cols FROM BMS_MAP WHERE id = ?",
                rs -> new BmsMapRecord(rs.getLong("id"), rs.getLong("mapset_id"), rs.getString("name"),
                        rs.getInt("size_rows"), rs.getInt("size_cols")),
                id);
    }

    public void insertBmsField(BmsFieldRecord field) {
        update("INSERT INTO BMS_FIELD(id, map_id, name, pos_row, pos_col, length, attrb) "
                        + "VALUES (?,?,?,?,?,?,?)",
                field.id(), field.mapId(), field.name(), field.posRow(), field.posCol(), field.length(),
                field.attrb());
    }

    public Optional<BmsFieldRecord> findBmsField(long id) {
        return queryOne(
                "SELECT id, map_id, name, pos_row, pos_col, length, attrb FROM BMS_FIELD WHERE id = ?",
                rs -> new BmsFieldRecord(rs.getLong("id"), rs.getLong("map_id"), rs.getString("name"),
                        rs.getInt("pos_row"), rs.getInt("pos_col"), rs.getInt("length"),
                        rs.getString("attrb")),
                id);
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
        update("INSERT INTO CALL_EDGE(id, from_node, to_node, kind, resolution, host_var, seq, line) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                edge.id(), edge.fromNode(), edge.toNode(), edge.kind(), edge.resolution(),
                edge.hostVar(), edge.seq(), edge.line());
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
            "SELECT id, from_node, to_node, kind, resolution, host_var, seq, line FROM CALL_EDGE";

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
        return new CallEdgeRecord(id, fromNode, toNode, kind, resolution, hostVar, seq,
                rs.wasNull() ? null : line);
    }

    // ---- FINDING ----

    public void insertFinding(FindingRecord finding) {
        update("INSERT INTO FINDING(id, rule_id, level, source_id, start_line, start_col, byte_offset, "
                        + "message, sarif_json) VALUES (?,?,?,?,?,?,?,?,?)",
                finding.id(), finding.ruleId(), finding.level(), finding.sourceId(), finding.startLine(),
                finding.startCol(), finding.byteOffset(), finding.message(), finding.sarifJson());
    }

    public Optional<FindingRecord> findFinding(long id) {
        return queryOne("SELECT id, rule_id, level, source_id, start_line, start_col, byte_offset, "
                + "message, sarif_json FROM FINDING WHERE id = ?", PersistenceDao::mapFinding, id);
    }

    public List<FindingRecord> findFindingsBySource(long sourceId) {
        return queryList("SELECT id, rule_id, level, source_id, start_line, start_col, byte_offset, "
                + "message, sarif_json FROM FINDING WHERE source_id = ?", PersistenceDao::mapFinding,
                sourceId);
    }

    private static FindingRecord mapFinding(ResultSet rs) throws SQLException {
        return new FindingRecord(rs.getLong("id"), rs.getString("rule_id"), rs.getString("level"),
                rs.getLong("source_id"), rs.getInt("start_line"), rs.getInt("start_col"),
                rs.getLong("byte_offset"), rs.getString("message"), rs.getString("sarif_json"));
    }

    // ---- SQL_STMT ----

    public void insertSqlStmt(SqlStmtRecord stmt) {
        update("INSERT INTO SQL_STMT(id, source_id, stmt_type, mangled_text, original_text) "
                        + "VALUES (?,?,?,?,?)",
                stmt.id(), stmt.sourceId(), stmt.stmtType(), stmt.mangledText(), stmt.originalText());
    }

    public Optional<SqlStmtRecord> findSqlStmt(long id) {
        return queryOne("SELECT id, source_id, stmt_type, mangled_text, original_text FROM SQL_STMT "
                + "WHERE id = ?", PersistenceDao::mapSqlStmt, id);
    }

    public List<SqlStmtRecord> findSqlStmtsBySource(long sourceId) {
        return queryList("SELECT id, source_id, stmt_type, mangled_text, original_text FROM SQL_STMT "
                + "WHERE source_id = ?", PersistenceDao::mapSqlStmt, sourceId);
    }

    private static SqlStmtRecord mapSqlStmt(ResultSet rs) throws SQLException {
        return new SqlStmtRecord(rs.getLong("id"), rs.getLong("source_id"), rs.getString("stmt_type"),
                rs.getString("mangled_text"), rs.getString("original_text"));
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

package jp.cobolinsight.persistence;

import jp.cobolinsight.persistence.model.BmsFieldRecord;
import jp.cobolinsight.persistence.model.BmsMapRecord;
import jp.cobolinsight.persistence.model.BmsMapsetRecord;
import jp.cobolinsight.persistence.model.CallEdgeRecord;
import jp.cobolinsight.persistence.model.EncodingInfoRecord;
import jp.cobolinsight.persistence.model.FindingRecord;
import jp.cobolinsight.persistence.model.LineMapRecord;
import jp.cobolinsight.persistence.model.NodeRecord;
import jp.cobolinsight.persistence.model.ParagraphRecord;
import jp.cobolinsight.persistence.model.ProgramRecord;
import jp.cobolinsight.persistence.model.SourceRecord;
import jp.cobolinsight.persistence.model.SqlStmtRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 12表の挿入・取得、呼出関係グラフの到達性問い合わせ、ソース単位の全消去を行う。 */
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

    /** 指定の資産フォルダから取り込んだ行だけを返す。他の資産フォルダの行は含まない。 */
    public List<SourceRecord> findSourcesByRoot(String root) {
        return queryList(SELECT_SOURCE + " WHERE root = ? ORDER BY id", PersistenceDao::mapSource,
                root);
    }

    /** 全資産フォルダを通じた SOURCE.id の最大値。新規行のID採番の起点にする。無ければ0。 */
    public long maxSourceId() {
        return queryOne("SELECT COALESCE(MAX(id), 0) AS max_id FROM SOURCE",
                rs -> rs.getLong("max_id")).orElse(0L);
    }

    /**
     * 指定ソースの行と、これを参照する子表の行をまとめて消す。子表の削除はDDLの
     * ON DELETE CASCADE に依るため、外部キー制約が有効な接続でのみ連鎖する。
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
        update("INSERT INTO CALL_EDGE(id, from_node, to_node, kind, resolution, host_var) "
                        + "VALUES (?,?,?,?,?,?)",
                edge.id(), edge.fromNode(), edge.toNode(), edge.kind(), edge.resolution(),
                edge.hostVar());
    }

    public Optional<CallEdgeRecord> findCallEdge(long id) {
        return queryOne("SELECT id, from_node, to_node, kind, resolution, host_var FROM CALL_EDGE "
                + "WHERE id = ?", PersistenceDao::mapCallEdge, id);
    }

    public List<CallEdgeRecord> findAllCallEdges() {
        return queryList("SELECT id, from_node, to_node, kind, resolution, host_var FROM CALL_EDGE "
                + "ORDER BY id", PersistenceDao::mapCallEdge);
    }

    public List<CallEdgeRecord> findEdgesFrom(long nodeId) {
        return queryList("SELECT id, from_node, to_node, kind, resolution, host_var FROM CALL_EDGE "
                + "WHERE from_node = ?", PersistenceDao::mapCallEdge, nodeId);
    }

    public List<CallEdgeRecord> findEdgesTo(long nodeId) {
        return queryList("SELECT id, from_node, to_node, kind, resolution, host_var FROM CALL_EDGE "
                + "WHERE to_node = ?", PersistenceDao::mapCallEdge, nodeId);
    }

    public void deleteCallEdgesFrom(long nodeId, String kind) {
        update("DELETE FROM CALL_EDGE WHERE from_node = ? AND kind = ?", nodeId, kind);
    }

    public void deleteCallEdgesTo(long nodeId, String kind) {
        update("DELETE FROM CALL_EDGE WHERE to_node = ? AND kind = ?", nodeId, kind);
    }

    // NODE・CALL_EDGE・FINDING では、ソース単位の解析が書く行と、呼出関係グラフの構築が書く行とを
    // IDの下限で分ける。前者は SOURCE.id を基点に採番し、後者は呼び出し側が定める下限以上に採番する。
    // 下限以上をまとめて消してから入れ直せば、ソース単位の行を残したままグラフだけを作り直せる。

    /** 指定ID以上のノードを一括削除する。ソースに対応しないノード(ジョブステップ・データセット等)の入替に使う。 */
    public void deleteNodesIdAtLeast(long idFloor) {
        update("DELETE FROM NODE WHERE id >= ?", idFloor);
    }

    /** 指定ID以上のエッジを一括削除する。呼出関係グラフのエッジの入替に使う。 */
    public void deleteCallEdgesIdAtLeast(long idFloor) {
        update("DELETE FROM CALL_EDGE WHERE id >= ?", idFloor);
    }

    /** 指定ID以上のfindingを一括削除する。呼出関係グラフの構築が生むfindingの入替に使う。 */
    public void deleteFindingsIdAtLeast(long idFloor) {
        update("DELETE FROM FINDING WHERE id >= ?", idFloor);
    }

    /** 再帰CTEにより、指定ノードから到達可能なノードID集合を返す(始点自身は循環時のみ含む)。 */
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
        return new CallEdgeRecord(rs.getLong("id"), rs.getLong("from_node"), rs.getLong("to_node"),
                rs.getString("kind"), rs.getString("resolution"), rs.getString("host_var"));
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

    /** 指定ソースの行対応を全消去する(translate の再実行を冪等にするため書込前に呼ぶ)。 */
    public void deleteLineMapsBySource(long cobolSourceId) {
        update("DELETE FROM LINE_MAP WHERE cobol_source_id = ?", cobolSourceId);
    }

    private static LineMapRecord mapLineMap(ResultSet rs) throws SQLException {
        return new LineMapRecord(rs.getLong("id"), rs.getLong("cobol_source_id"),
                rs.getInt("cobol_line_start"), rs.getInt("cobol_line_end"), rs.getString("gen_file"),
                rs.getInt("gen_line_start"), rs.getInt("gen_line_end"), rs.getString("kind"),
                rs.getString("note"), rs.getString("anchor_id"));
    }

    // ---- トランザクション ----

    /** 渡した処理の全体を単一のトランザクションとして実行し、実行時例外が出た場合はロールバックして投げ直す。 */
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

    // ---- JDBCヘルパ ----

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

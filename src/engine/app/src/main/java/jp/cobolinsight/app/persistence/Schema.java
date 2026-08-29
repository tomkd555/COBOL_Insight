package jp.cobolinsight.app.persistence;

/** 解析結果を保持する13表のDDL。 */
final class Schema {

    /** データベースファイルの user_version へ記録するスキーマの版数。 */
    static final int VERSION = 3;

    static final String[] CREATE_STATEMENTS = {
            """
            CREATE TABLE SOURCE (
                id INTEGER PRIMARY KEY,
                root TEXT NOT NULL,
                path TEXT NOT NULL,
                codepage TEXT,
                content_hash TEXT NOT NULL,
                byte_size INTEGER NOT NULL
            )
            """,
            "CREATE INDEX idx_source_root_path ON SOURCE(root, path)",
            """
            CREATE TABLE ENCODING_INFO (
                source_id INTEGER PRIMARY KEY REFERENCES SOURCE(id) ON DELETE CASCADE,
                detected_charset TEXT NOT NULL,
                confidence REAL NOT NULL,
                manual_override INTEGER NOT NULL,
                so_si_present INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE BMS_MAPSET (
                id INTEGER PRIMARY KEY,
                source_id INTEGER NOT NULL REFERENCES SOURCE(id) ON DELETE CASCADE,
                name TEXT NOT NULL
            )
            """,
            "CREATE INDEX idx_bms_mapset_source ON BMS_MAPSET(source_id)",
            """
            CREATE TABLE BMS_MAP (
                id INTEGER PRIMARY KEY,
                mapset_id INTEGER NOT NULL REFERENCES BMS_MAPSET(id) ON DELETE CASCADE,
                name TEXT NOT NULL,
                size_rows INTEGER NOT NULL,
                size_cols INTEGER NOT NULL
            )
            """,
            "CREATE INDEX idx_bms_map_mapset ON BMS_MAP(mapset_id)",
            """
            CREATE TABLE BMS_FIELD (
                id INTEGER PRIMARY KEY,
                map_id INTEGER NOT NULL REFERENCES BMS_MAP(id) ON DELETE CASCADE,
                name TEXT NOT NULL,
                pos_row INTEGER NOT NULL,
                pos_col INTEGER NOT NULL,
                length INTEGER NOT NULL,
                attrb TEXT
            )
            """,
            "CREATE INDEX idx_bms_field_map ON BMS_FIELD(map_id)",
            """
            CREATE TABLE PROGRAM (
                id INTEGER PRIMARY KEY,
                source_id INTEGER NOT NULL REFERENCES SOURCE(id) ON DELETE CASCADE,
                program_id_name TEXT NOT NULL
            )
            """,
            "CREATE INDEX idx_program_source ON PROGRAM(source_id)",
            """
            CREATE TABLE PARAGRAPH (
                id INTEGER PRIMARY KEY,
                program_id INTEGER NOT NULL REFERENCES PROGRAM(id) ON DELETE CASCADE,
                name TEXT NOT NULL,
                start_line INTEGER NOT NULL,
                end_line INTEGER NOT NULL
            )
            """,
            "CREATE INDEX idx_paragraph_program ON PARAGRAPH(program_id)",
            """
            CREATE TABLE PARAGRAPH_EDGE (
                id INTEGER PRIMARY KEY,
                program_source_id INTEGER NOT NULL REFERENCES SOURCE(id) ON DELETE CASCADE,
                from_paragraph INTEGER NOT NULL REFERENCES PARAGRAPH(id) ON DELETE CASCADE,
                to_paragraph INTEGER REFERENCES PARAGRAPH(id) ON DELETE CASCADE,
                to_name TEXT NOT NULL,
                kind TEXT NOT NULL CHECK (kind IN ('PERFORM','GOTO','FALLTHROUGH')),
                line INTEGER,
                seq INTEGER NOT NULL
            )
            """,
            "CREATE INDEX idx_paragraph_edge_program ON PARAGRAPH_EDGE(program_source_id)",
            "CREATE INDEX idx_paragraph_edge_from ON PARAGRAPH_EDGE(from_paragraph)",
            """
            CREATE TABLE NODE (
                id INTEGER PRIMARY KEY,
                type TEXT NOT NULL,
                label TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE CALL_EDGE (
                id INTEGER PRIMARY KEY,
                from_node INTEGER NOT NULL REFERENCES NODE(id) ON DELETE CASCADE,
                to_node INTEGER NOT NULL REFERENCES NODE(id) ON DELETE CASCADE,
                kind TEXT NOT NULL,
                resolution TEXT,
                host_var TEXT,
                seq INTEGER NOT NULL DEFAULT 0,
                line INTEGER
            )
            """,
            "CREATE INDEX idx_call_edge_from ON CALL_EDGE(from_node)",
            "CREATE INDEX idx_call_edge_to ON CALL_EDGE(to_node)",
            """
            CREATE TABLE FINDING (
                id INTEGER PRIMARY KEY,
                rule_id TEXT NOT NULL,
                level TEXT NOT NULL,
                source_id INTEGER NOT NULL REFERENCES SOURCE(id) ON DELETE CASCADE,
                start_line INTEGER NOT NULL,
                start_col INTEGER NOT NULL,
                byte_offset INTEGER NOT NULL,
                message TEXT NOT NULL,
                sarif_json TEXT NOT NULL
            )
            """,
            "CREATE INDEX idx_finding_source ON FINDING(source_id)",
            """
            CREATE TABLE SQL_STMT (
                id INTEGER PRIMARY KEY,
                source_id INTEGER NOT NULL REFERENCES SOURCE(id) ON DELETE CASCADE,
                stmt_type TEXT NOT NULL,
                mangled_text TEXT NOT NULL,
                original_text TEXT NOT NULL
            )
            """,
            "CREATE INDEX idx_sql_stmt_source ON SQL_STMT(source_id)",
            """
            CREATE TABLE LINE_MAP (
                id INTEGER PRIMARY KEY,
                cobol_source_id INTEGER NOT NULL REFERENCES SOURCE(id) ON DELETE CASCADE,
                cobol_line_start INTEGER NOT NULL,
                cobol_line_end INTEGER NOT NULL,
                gen_file TEXT NOT NULL,
                gen_line_start INTEGER NOT NULL,
                gen_line_end INTEGER NOT NULL,
                kind TEXT NOT NULL,
                note TEXT NOT NULL,
                anchor_id TEXT NOT NULL
            )
            """,
            "CREATE INDEX idx_line_map_source ON LINE_MAP(cobol_source_id)"
    };

    /**
     * 版数の古いファイルを作り直すための削除文。外部キーの参照先を後に消すため、子表から並べる。
     * 索引は表と一緒に消えるため個別に並べない。
     */
    static final String[] DROP_STATEMENTS = {
            "DROP TABLE IF EXISTS LINE_MAP",
            "DROP TABLE IF EXISTS SQL_STMT",
            "DROP TABLE IF EXISTS FINDING",
            "DROP TABLE IF EXISTS CALL_EDGE",
            "DROP TABLE IF EXISTS NODE",
            "DROP TABLE IF EXISTS PARAGRAPH_EDGE",
            "DROP TABLE IF EXISTS PARAGRAPH",
            "DROP TABLE IF EXISTS PROGRAM",
            "DROP TABLE IF EXISTS BMS_FIELD",
            "DROP TABLE IF EXISTS BMS_MAP",
            "DROP TABLE IF EXISTS BMS_MAPSET",
            "DROP TABLE IF EXISTS ENCODING_INFO",
            "DROP TABLE IF EXISTS SOURCE"
    };

    private Schema() {
    }
}

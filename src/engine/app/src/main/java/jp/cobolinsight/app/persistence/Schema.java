package jp.cobolinsight.app.persistence;

/** The DDL for the tables that hold analysis results. */
final class Schema {

    /** The schema version recorded in the database file's user_version. */
    static final int VERSION = 5;

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
                line INTEGER,
                access TEXT,
                attrs_json TEXT
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
                message TEXT NOT NULL
            )
            """,
            "CREATE INDEX idx_finding_source ON FINDING(source_id)",
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
            "CREATE INDEX idx_line_map_source ON LINE_MAP(cobol_source_id)",
            """
            CREATE TABLE JCL_STEP (
                id INTEGER PRIMARY KEY,
                source_id INTEGER NOT NULL REFERENCES SOURCE(id) ON DELETE CASCADE,
                job_name TEXT NOT NULL,
                seq INTEGER NOT NULL,
                step_name TEXT NOT NULL,
                exec_kind TEXT NOT NULL,
                target TEXT NOT NULL,
                proc_step TEXT,
                line INTEGER,
                file TEXT NOT NULL,
                detail_json TEXT NOT NULL
            )
            """,
            "CREATE INDEX idx_jcl_step_source ON JCL_STEP(source_id)",
            """
            CREATE TABLE JCL_DD (
                id INTEGER PRIMARY KEY,
                step_id INTEGER NOT NULL REFERENCES JCL_STEP(id) ON DELETE CASCADE,
                seq INTEGER NOT NULL,
                dd_name TEXT NOT NULL,
                dsn TEXT,
                access TEXT,
                line INTEGER,
                file TEXT NOT NULL,
                detail_json TEXT NOT NULL
            )
            """,
            "CREATE INDEX idx_jcl_dd_step ON JCL_DD(step_id)",
            """
            CREATE TABLE SQL_STMT (
                id INTEGER PRIMARY KEY,
                source_id INTEGER NOT NULL REFERENCES SOURCE(id) ON DELETE CASCADE,
                program_id INTEGER REFERENCES PROGRAM(id) ON DELETE CASCADE,
                seq INTEGER NOT NULL,
                kind TEXT NOT NULL,
                cursor_name TEXT,
                line INTEGER NOT NULL,
                end_line INTEGER NOT NULL,
                analysis TEXT NOT NULL,
                text TEXT NOT NULL,
                file TEXT NOT NULL,
                detail_json TEXT NOT NULL
            )
            """,
            "CREATE INDEX idx_sql_stmt_source ON SQL_STMT(source_id)",
            """
            CREATE TABLE SQL_TABLE_USE (
                id INTEGER PRIMARY KEY,
                stmt_id INTEGER NOT NULL REFERENCES SQL_STMT(id) ON DELETE CASCADE,
                table_name TEXT NOT NULL,
                access TEXT NOT NULL
            )
            """,
            "CREATE INDEX idx_sql_table_use_stmt ON SQL_TABLE_USE(stmt_id)",
            """
            CREATE TABLE SQL_COLUMN_USE (
                id INTEGER PRIMARY KEY,
                stmt_id INTEGER NOT NULL REFERENCES SQL_STMT(id) ON DELETE CASCADE,
                table_name TEXT,
                column_name TEXT NOT NULL
            )
            """,
            "CREATE INDEX idx_sql_column_use_stmt ON SQL_COLUMN_USE(stmt_id)"
    };

    /**
     * The drop statements used to rebuild a file with an old schema version. Ordered from child
     * tables first, since a foreign key's referent must be dropped later. Indexes are dropped
     * along with their table, so they are not listed separately. Tables the schema no longer
     * creates are listed too, so that rebuilding an old file leaves exactly the current schema.
     */
    static final String[] DROP_STATEMENTS = {
            "DROP TABLE IF EXISTS BMS_FIELD",
            "DROP TABLE IF EXISTS BMS_MAP",
            "DROP TABLE IF EXISTS BMS_MAPSET",
            "DROP TABLE IF EXISTS ENCODING_INFO",
            "DROP TABLE IF EXISTS SQL_COLUMN_USE",
            "DROP TABLE IF EXISTS SQL_TABLE_USE",
            "DROP TABLE IF EXISTS SQL_STMT",
            "DROP TABLE IF EXISTS JCL_DD",
            "DROP TABLE IF EXISTS JCL_STEP",
            "DROP TABLE IF EXISTS LINE_MAP",
            "DROP TABLE IF EXISTS FINDING",
            "DROP TABLE IF EXISTS CALL_EDGE",
            "DROP TABLE IF EXISTS NODE",
            "DROP TABLE IF EXISTS PARAGRAPH_EDGE",
            "DROP TABLE IF EXISTS PARAGRAPH",
            "DROP TABLE IF EXISTS PROGRAM",
            "DROP TABLE IF EXISTS SOURCE"
    };

    private Schema() {
    }
}

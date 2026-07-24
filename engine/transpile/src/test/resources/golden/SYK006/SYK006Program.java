package cobolinsight.generated;

/**
 * SYK006 の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。
 * データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。
 */
public final class SYK006Program {

    String STKIN_商品コード = "";
    String STKIN_倉庫コード = "";
    long STKIN_増減数量;
    String STKIN_更新区分 = "";
    String SYK3_商品コード = "";
    String SYK3_倉庫コード = "";
    long SYK3_在庫数量;
    long SYK3_引当可能数量;
    String SYK3_更新区分 = "";
    long SYK3_処理日;
    String HOST_商品コード = "";
    String HOST_倉庫コード = "";
    long HOST_在庫数量;
    long HOST_引当数量;
    long HOST_増減数量;
    String WS_STKIN_STATUS = "";
    String WS_STKEXTR_STATUS = "";
    String WS_EOF_FLAG = "";  // VALUE 'N'
    long WS_処理件数;  // VALUE ZERO
    long WS_エラー件数;  // VALUE ZERO
    long WS_エラー件数INDEX;  // VALUE ZERO
    String WS_メッセージ区分 = "";
    String WS_メッセージ内容 = "";
    String[] WS_エラー商品 = new String[20];

    void call_program(String name, Object... args) {
        // CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。
    }
    // 作業部 SQL 指令(直訳不能・注記のみ): EXEC SQL INCLUDE SQLCA END-EXEC.
    // 作業部 SQL 指令(直訳不能・注記のみ): EXEC SQL BEGIN DECLARE SECTION END-EXEC.
    // 作業部 SQL 指令(直訳不能・注記のみ): EXEC SQL END DECLARE SECTION END-EXEC.

    void _0000_メイン処理() {
        _1000_初期化処理();
        while (!(WS_EOF_FLAG.equals("Y"))) {
            _2000_在庫更新処理();
        }
        _3000_抽出ファイル出力();
        _8000_終了処理();
        if (true) return; // STOP RUN
    }

    void _1000_初期化処理() {
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // OPEN INPUT  STKIN
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // OPEN OUTPUT STKEXTR
        _1100_トランザクション読込();
    }

    void _1100_トランザクション読込() {
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // READ STKIN INTO STKIN-レコード
        // AT END
        // MOVE 'Y' TO WS-EOF-FLAG
        // END-READ
    }

    void _2000_在庫更新処理() {
        WS_処理件数 = WS_処理件数 + 1;
        HOST_商品コード = STKIN_商品コード;
        HOST_倉庫コード = STKIN_倉庫コード;
        // [直訳不能: EXEC SQL SELECT は直訳不能]
        // EXEC SQL
        // SELECT ZAIKO_SU INTO :HOST-在庫数量
        // FROM SYKDB.ZAIKOM
        // WHERE SHOHIN_CD = :HOST-商品コード
        // AND SOKO_CD   = :HOST-倉庫コード
        // END-EXEC
        if (true) throw new UnsupportedOperationException("EXEC SQL SELECT は直訳不能");
        if (Boolean.TRUE /* SQLCODE = 0 */) {
            _2100_在庫数更新();
        } else if (Boolean.TRUE /* SQLCODE = 100 */) {
            _2200_在庫マスタ新規登録();
        } else {
            _2900_エラー商品登録();
        }
        _1100_トランザクション読込();
    }

    void _2100_在庫数更新() {
        HOST_増減数量 = STKIN_増減数量;
        // [直訳不能: EXEC SQL UPDATE は直訳不能]
        // EXEC SQL
        // UPDATE SYKDB.ZAIKOM
        // SET ZAIKO_SU = ZAIKO_SU + :HOST-増減数量
        // WHERE SHOHIN_CD = :HOST-商品コード
        // AND SOKO_CD   = :HOST-倉庫コード
        // END-EXEC.
        if (true) throw new UnsupportedOperationException("EXEC SQL UPDATE は直訳不能");
    }

    void _2200_在庫マスタ新規登録() {
        HOST_増減数量 = STKIN_増減数量;
        // [直訳不能: EXEC SQL INSERT は直訳不能]
        // EXEC SQL
        // INSERT INTO SYKDB.ZAIKOM
        // (SHOHIN_CD, SOKO_CD, ZAIKO_SU,
        // HIKIATE_SU, KOSHIN_BI)
        // VALUES (:HOST-商品コード, :HOST-倉庫コード,
        // :HOST-増減数量, 0, '00000000')
        // END-EXEC
        if (true) throw new UnsupportedOperationException("EXEC SQL INSERT は直訳不能");
        if (Boolean.TRUE /* SQLCODE NOT = 0 */) {
            WS_メッセージ区分 = "E1";
            WS_メッセージ内容 = "ZAIKO INSERT ERROR";
            // CALL SYK005 USING WS-メッセージ区分, WS-メッセージ内容 — BY REFERENCE 渡し。プログラム間の実呼出・値渡しは模擬しない
            call_program("SYK005", "WS-メッセージ区分", "WS-メッセージ内容");
        }
    }

    void _2900_エラー商品登録() {
        WS_エラー件数 = WS_エラー件数 + 1;
        WS_エラー件数INDEX = WS_エラー件数INDEX + 1;
        WS_エラー商品[(int) (WS_エラー件数INDEX - 1)] = STKIN_商品コード;
        WS_メッセージ区分 = "E1";
        WS_メッセージ内容 = "ZAIKO SELECT ERROR";
        // CALL SYK005 USING WS-メッセージ区分, WS-メッセージ内容 — BY REFERENCE 渡し。プログラム間の実呼出・値渡しは模擬しない
        call_program("SYK005", "WS-メッセージ区分", "WS-メッセージ内容");
    }

    void _3000_抽出ファイル出力() {
        // [直訳不能: EXEC SQL DECLARE CURSOR は直訳不能]
        // EXEC SQL
        // DECLARE SYKZAIKOCUR CURSOR FOR
        // SELECT SHOHIN_CD, SOKO_CD, ZAIKO_SU, HIKIATE_SU
        // FROM SYKDB.ZAIKOM
        // END-EXEC
        if (true) throw new UnsupportedOperationException("EXEC SQL DECLARE CURSOR は直訳不能");
        // [直訳不能: EXEC SQL OPEN は直訳不能]
        // EXEC SQL
        // OPEN SYKZAIKOCUR
        // END-EXEC
        if (true) throw new UnsupportedOperationException("EXEC SQL OPEN は直訳不能");
        while (!(Boolean.TRUE /* SQLCODE = 100 */)) {
            _3100_抽出データフェッチ();
        }
        // [直訳不能: EXEC SQL CLOSE は直訳不能]
        // EXEC SQL
        // CLOSE SYKZAIKOCUR
        // END-EXEC.
        if (true) throw new UnsupportedOperationException("EXEC SQL CLOSE は直訳不能");
    }

    void _3100_抽出データフェッチ() {
        // [直訳不能: EXEC SQL FETCH は直訳不能]
        // EXEC SQL
        // FETCH SYKZAIKOCUR
        // INTO :HOST-商品コード, :HOST-倉庫コード,
        // :HOST-在庫数量, :HOST-引当数量
        // END-EXEC
        if (true) throw new UnsupportedOperationException("EXEC SQL FETCH は直訳不能");
        if (Boolean.TRUE /* SQLCODE = 0 */) {
            SYK3_商品コード = HOST_商品コード;
            SYK3_倉庫コード = HOST_倉庫コード;
            SYK3_在庫数量 = HOST_在庫数量;
            SYK3_引当可能数量 = HOST_引当数量;
            SYK3_更新区分 = "1";
            // [直訳不能: MOVE の構文を解釈できない(集団/CORR/参照修正)]
            // MOVE FUNCTION CURRENT-DATE(1:8) TO SYK3-処理日
            // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
            // WRITE SYK3-在庫抽出レコード
        }
    }

    void _8000_終了処理() {
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // CLOSE STKIN
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // CLOSE STKEXTR
        System.out.println("SYK006 処理件数   = " + WS_処理件数);
        System.out.println("SYK006 エラー件数 = " + WS_エラー件数);
    }
}

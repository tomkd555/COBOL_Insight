package cobolinsight.generated;

/**
 * SYK007 の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。
 * データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。
 * 逐語対訳であり、演算や桁詰めの最適化は行わない。桁数・小数スケール・固定長の空白詰めは
 * フラット変数では再現せず、原文の PICTURE 句とレコードクラスのバイト列アクセサを正とする。
 */
public final class SYK007Program {

    String SYK3_商品コード = "";
    String SYK3_倉庫コード = "";
    long SYK3_在庫数量;
    long SYK3_引当可能数量;
    String SYK3_更新区分 = "";
    long SYK3_処理日;
    String HOST_商品コード = "";
    String HOST_倉庫コード = "";
    String HOST_倉庫名 = "";
    long HOST_引当数量;
    String WS_STKEXTR_STATUS = "";
    String WS_EOF_FLAG = "";  // VALUE 'N'
    long WS_処理件数;  // VALUE ZERO
    long WS_引当率;

    void call_program(String name, Object... args) {
        // CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。
    }
    // 作業部 SQL 指令(直訳不能・注記のみ): EXEC SQL INCLUDE SQLCA END-EXEC.
    // 作業部 SQL 指令(直訳不能・注記のみ): EXEC SQL BEGIN DECLARE SECTION END-EXEC.
    // 作業部 SQL 指令(直訳不能・注記のみ): EXEC SQL END DECLARE SECTION END-EXEC.

    void _0000_メイン処理() {
        _1000_初期化処理();
        while (!(WS_EOF_FLAG.equals("Y"))) {
            _2000_在庫照会処理();
        }
        _8000_終了処理();
        if (true) return; // STOP RUN
    }

    void _1000_初期化処理() {
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // OPEN INPUT STKEXTR
        _1100_抽出データ読込();
    }

    void _1100_抽出データ読込() {
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // READ STKEXTR
        // AT END
        // MOVE 'Y' TO WS-EOF-FLAG
        // END-READ
    }

    void _2000_在庫照会処理() {
        WS_処理件数 = WS_処理件数 + 1;
        HOST_商品コード = SYK3_商品コード;
        HOST_倉庫コード = SYK3_倉庫コード;
        // [直訳不能: EXEC SQL SELECT は直訳不能]
        // EXEC SQL
        // SELECT SOKO_NM INTO :HOST-倉庫名
        // FROM SYKDB.SOKOM
        // WHERE SOKO_CD = :HOST-倉庫コード
        // END-EXEC
        if (true) throw new UnsupportedOperationException("EXEC SQL SELECT は直訳不能");
        _2100_引当率計算();
        _2200_引当数量更新();
        _1100_抽出データ読込();
    }

    void _2100_引当率計算() {
        WS_引当率 = (SYK3_引当可能数量 * 100) / SYK3_在庫数量;
    }

    void _2200_引当数量更新() {
        HOST_引当数量 = SYK3_引当可能数量;
        // [直訳不能: EXEC SQL UPDATE は直訳不能]
        // EXEC SQL
        // UPDATE SYKDB.ZAIKOM
        // SET HIKIATE_SU = :HOST-引当数量
        // WHERE SHOHIN_CD = :HOST-商品コード
        // AND SOKO_CD   = :HOST-倉庫コード
        // END-EXEC.
        if (true) throw new UnsupportedOperationException("EXEC SQL UPDATE は直訳不能");
    }

    void _8000_終了処理() {
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // CLOSE STKEXTR
        System.out.println("SYK007 処理件数 = " + WS_処理件数);
    }
}

package cobolinsight.generated;

/**
 * SYK008 の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。
 * データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。
 */
public final class SYK008Program {

    String WS_ORDNO_入力 = "";
    String WS_MSG_出力 = "";
    String WS_検査結果 = "";
    long WS_RESPコード;
    long WS_RESP2コード;

    void call_program(String name, Object... args) {
        // CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。
    }

    void _0000_メイン処理() {
        // [直訳不能: EXEC CICS RECEIVE MAP は直訳不能 (INTO=WS-受注入力マップ, MAP=SYKM01, MAPSET=SYKMAP1)]
        // EXEC CICS
        // RECEIVE MAP('SYKM01')
        // MAPSET('SYKMAP1')
        // INTO(WS-受注入力マップ)
        // END-EXEC
        if (true) throw new UnsupportedOperationException("EXEC CICS RECEIVE MAP は直訳不能");
        _1000_受注番号検査();
        if (WS_検査結果.equals("E")) {
            _2000_エラーメッセージ表示();
        } else {
            _3000_次画面遷移();
        }
    }

    void _1000_受注番号検査() {
        if (Boolean.TRUE /* WS-ORDNO-入力 = SPACES OR WS-ORDNO-入力 */) {
            WS_検査結果 = "E";
            WS_MSG_出力 = "受注番号が不正です";
        } else {
            WS_検査結果 = " ";
        }
    }

    void _2000_エラーメッセージ表示() {
        // [直訳不能: EXEC CICS SEND MAP は直訳不能 (FROM=WS-受注入力マップ, MAP=SYKM99, MAPSET=SYKMAP1, RESP=WS-RESPコード, RESP2=WS-RESP2コード)]
        // EXEC CICS
        // SEND MAP('SYKM99')
        // MAPSET('SYKMAP1')
        // FROM(WS-受注入力マップ)
        // RESP(WS-RESPコード)
        // RESP2(WS-RESP2コード)
        // END-EXEC
        if (true) throw new UnsupportedOperationException("EXEC CICS SEND MAP は直訳不能");
        if (WS_RESPコード != 0) {
            System.out.println("SYK008 SEND MAPエラー RESP=" + WS_RESPコード);
        }
        // [直訳不能: EXEC CICS RETURN は直訳不能 (RESP=WS-RESPコード, RESP2=WS-RESP2コード, TRANSID=SYK8)]
        // EXEC CICS
        // RETURN TRANSID('SYK8')
        // RESP(WS-RESPコード)
        // RESP2(WS-RESP2コード)
        // END-EXEC
        if (true) throw new UnsupportedOperationException("EXEC CICS RETURN は直訳不能");
        if (WS_RESPコード != 0) {
            System.out.println("SYK008 RETURNエラー RESP=" + WS_RESPコード);
        }
    }

    void _3000_次画面遷移() {
        // [直訳不能: EXEC CICS XCTL は直訳不能 (PROGRAM=SYK009, RESP=WS-RESPコード, RESP2=WS-RESP2コード)]
        // EXEC CICS
        // XCTL PROGRAM('SYK009')
        // RESP(WS-RESPコード)
        // RESP2(WS-RESP2コード)
        // END-EXEC
        if (true) throw new UnsupportedOperationException("EXEC CICS XCTL は直訳不能");
        if (WS_RESPコード != 0) {
            System.out.println("SYK008 XCTLエラー RESP=" + WS_RESPコード);
        }
    }
}

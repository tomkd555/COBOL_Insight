package cobolinsight.generated;

/**
 * SYK001 の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。
 * データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。
 * 逐語対訳であり、演算や桁詰めの最適化は行わない。桁数・小数スケール・固定長の空白詰めは
 * フラット変数では再現せず、原文の PICTURE 句とレコードクラスのバイト列アクセサを正とする。
 */
public final class SYK001Program {

    String ORD1_受注番号 = "";
    long ORD1_受注日;
    long ORD1_受注日_年;
    long ORD1_受注日_月;
    long ORD1_受注日_日;
    String ORD1_得意先コード = "";
    long ORD1_受注金額合計;
    long ORD1_明細件数;
    String[] ORD1_商品コード = new String[10];
    long[] ORD1_数量 = new long[10];
    long[] ORD1_単価 = new long[10];
    long[] ORD1_金額 = new long[10];
    String ORD1_処理区分 = "";
    String VALID_REC = "";
    String ERR_受注番号 = "";
    String ERR_エラー内容 = "";
    String FILLER = "";
    String WS_ORDIN_STATUS = "";
    String WS_ORDVALID_STATUS = "";
    String WS_ORDERR_STATUS = "";
    String WS_EOF_FLAG = "";  // VALUE 'N'
    long WS_IDX;
    long WS_検証金額;
    long WS_上限金額;  // VALUE 10000000.00
    long WS_印字用金額;
    long WS_合計チェック;  // VALUE ZERO
    long WS_エラー件数;  // VALUE ZERO
    long WS_処理件数;  // VALUE ZERO
    String WS_チェック結果 = "";
    String WS_エラーメッセージ = "";

    void call_program(String name, Object... args) {
        // CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。
    }

    void _0000_メイン処理() {
        _1000_初期化処理();
        while (!(WS_EOF_FLAG.equals("Y"))) {
            _2000_受注データ処理();
        }
        _8000_終了処理();
        if (true) return; // STOP RUN
    }

    void _1000_初期化処理() {
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // OPEN INPUT  ORDIN
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // OPEN OUTPUT ORDVALID
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // OPEN OUTPUT ORDERR
        _1100_受注データ読込();
    }

    void _1100_受注データ読込() {
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // READ ORDIN INTO ORD1-受注レコード
        // AT END
        // MOVE 'Y' TO WS-EOF-FLAG
        // END-READ
    }

    void _2000_受注データ処理() {
        WS_処理件数 = WS_処理件数 + 1;
        _2100_明細検証();
        _2200_結果判定();
        _1100_受注データ読込();
    }

    void _2100_明細検証() {
        if (ORD1_処理区分.equals("1")) {
            _2110_新規登録検証();
        } else if (ORD1_処理区分.equals("2")) {
            _2120_訂正検証();
        } else if (ORD1_処理区分.equals("9")) {
            ; // CONTINUE
        } else {
            WS_エラーメッセージ = "処理区分コード不正";
        }
    }

    void _2110_新規登録検証() {
        WS_検証金額 = ORD1_受注金額合計;
        // CALL SYK003 USING ORD1-受注レコード, WS-チェック結果 — BY REFERENCE 渡し。プログラム間の実呼出・値渡しは模擬しない
        call_program("SYK003", "ORD1-受注レコード", "WS-チェック結果");
        WS_合計チェック = 0;
        WS_IDX = 1;
        while (!(WS_IDX > ORD1_明細件数)) {
            WS_合計チェック = WS_合計チェック + ORD1_金額[(int) (WS_IDX - 1)];
            WS_IDX = WS_IDX + 1;
        }
    }

    void _2120_訂正検証() {
        WS_検証金額 = ORD1_受注金額合計;
    }

    void _2200_結果判定() {
        if (WS_検証金額 > WS_上限金額) {
            WS_エラーメッセージ = "金額超過エラー";
            WS_エラー件数 = WS_エラー件数 + 1;
            ERR_受注番号 = ORD1_受注番号;
            ERR_エラー内容 = WS_エラーメッセージ;
            // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
            // WRITE ERROR-REC
        } else {
            WS_印字用金額 = ORD1_受注金額合計;
            // [直訳不能: MOVE 送信項目が集団または未解決]
            // MOVE ORD1-受注レコード       TO VALID-REC
            // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
            // WRITE VALID-REC
        }
    }

    void _8000_終了処理() {
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // CLOSE ORDIN
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // CLOSE ORDVALID
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // CLOSE ORDERR
        System.out.println("SYK001 処理件数   = " + WS_処理件数);
        System.out.println("SYK001 エラー件数 = " + WS_エラー件数);
    }
}

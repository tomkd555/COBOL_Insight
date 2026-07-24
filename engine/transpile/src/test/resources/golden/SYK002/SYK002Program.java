package cobolinsight.generated;

/**
 * SYK002 の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。
 * データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。
 */
public final class SYK002Program {

    String IN1_受注番号 = "";
    long IN1_受注日;
    long IN1_受注日_年;
    long IN1_受注日_月;
    long IN1_受注日_日;
    String IN1_得意先コード = "";
    long IN1_受注金額合計;
    long IN1_明細件数;
    String[] IN1_商品コード = new String[10];
    long[] IN1_数量 = new long[10];
    long[] IN1_単価 = new long[10];
    long[] IN1_金額 = new long[10];
    String IN1_処理区分 = "";
    String SYK2_受注番号 = "";
    String SYK2_得意先コード = "";
    long SYK2_受注日;
    long SYK2_受注金額合計;
    String SYK2_入金状況 = "";
    String SYK2_登録日時 = "";
    String SYK2_更新日時 = "";
    String SYK2_予備領域 = "";
    String WS_ORDVALID_STATUS = "";
    String WS_MASTER_STATUS = "";
    String WS_EOF_FLAG = "";  // VALUE 'N'
    String WS_マスタ有無 = "";
    String WS_PROG_NAME = "";
    String WS_引当可否 = "";
    long WS_要求数量;
    long WS_金額集計エリア;  // VALUE ZERO
    long WS_新規件数;  // VALUE ZERO
    long WS_更新件数;  // VALUE ZERO

    void call_program(String name, Object... args) {
        // CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。
    }

    void _0000_メイン処理() {
        _1000_初期化処理();
        _2000_受注データ読込();
        while (!(WS_EOF_FLAG.equals("Y"))) {
            _3000_登録メイン();
        }
        _8000_終了処理();
        if (true) return; // STOP RUN
    }

    void _1000_初期化処理() {
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // OPEN INPUT ORDVALID
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // OPEN I-O   ORDMSTR
        WS_PROG_NAME = "SYK004";
    }

    void _2000_受注データ読込() {
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // READ ORDVALID INTO IN1-受注レコード
        // AT END
        // MOVE 'Y' TO WS-EOF-FLAG
        // END-READ
    }

    void _3000_登録メイン() {
        _3010_マスタ検索();
        if (WS_マスタ有無.equals("N")) {
            _3020_新規登録処理();
        } else {
            _3030_更新登録処理();
        }
        _2000_受注データ読込();
    }

    void _3010_マスタ検索() {
        SYK2_受注番号 = IN1_受注番号;
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // READ ORDMSTR
        // INVALID KEY
        // MOVE 'N' TO WS-マスタ有無
        // NOT INVALID KEY
        // MOVE 'Y' TO WS-マスタ有無
        // END-READ
        if (WS_MASTER_STATUS.equals("93")) {
            _9000_緊急再更新処理();
        }
    }

    void _3020_新規登録処理() {
        SYK2_受注番号 = IN1_受注番号;
        SYK2_得意先コード = IN1_得意先コード;
        SYK2_受注日 = IN1_受注日;
        SYK2_受注金額合計 = IN1_受注金額合計;
        SYK2_入金状況 = "N";
        SYK2_登録日時 = " ";
        SYK2_更新日時 = " ";
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // WRITE SYK2-受注マスタレコード
        // INVALID KEY
        // DISPLAY 'SYK002 マスタ登録エラー ' SYK2-受注番号
        // END-WRITE
        WS_新規件数 = WS_新規件数 + 1;
        WS_要求数量 = IN1_数量[(int) (1 - 1)];
        // CALL WS-PROG-NAME USING IN1-商品コード(1), WS-要求数量, WS-引当可否 — BY REFERENCE 渡し。プログラム間の実呼出・値渡しは模擬しない
        call_program("WS-PROG-NAME", "IN1-商品コード(1)", "WS-要求数量", "WS-引当可否");
    }

    void _3030_更新登録処理() {
        WS_金額集計エリア = SYK2_受注金額合計;
        // PERFORM THRU: _4000_マスタ更新処理 .. _4000_マスタ更新処理_EXIT
        _4000_マスタ更新処理();
        _4000_マスタ更新処理_EXIT();
        WS_更新件数 = WS_更新件数 + 1;
    }

    void _9000_緊急再更新処理() {
        System.out.println("SYK002 レコードロック検出のため再更新を実施");
        // [直訳不能: GO TO 4010-マスタ書込 を以降の順次実行へ構造化]
        // GO TO 4010-マスタ書込
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ / GO TO 構造化による複製(元の段落と重複)]
        // REWRITE SYK2-受注マスタレコード
        ; // EXIT
    }

    void _4000_マスタ更新処理() {
        SYK2_受注金額合計 = IN1_受注金額合計;
    }

    void _4010_マスタ書込() {
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // REWRITE SYK2-受注マスタレコード
    }

    void _4000_マスタ更新処理_EXIT() {
        ; // EXIT
    }

    void _8000_終了処理() {
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // CLOSE ORDVALID
        // [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        // CLOSE ORDMSTR
        System.out.println("SYK002 新規件数 = " + WS_新規件数);
        System.out.println("SYK002 更新件数 = " + WS_更新件数);
    }
}

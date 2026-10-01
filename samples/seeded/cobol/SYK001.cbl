      *================================================================*
      *  PROGRAM-ID : SYK001                                          *
      *  機能       : 受注データ検証バッチ（日次）                    *
      *  処理概要   : 受注日次ファイル(ORDIN)を読み込み、受注金額      *
      *               合計が上限金額を超えるレコードをORDERRへ、       *
      *               それ以外をORDVALIDへ振り分けて出力する。         *
      *  起動元     : SYKD010 STEP010 / SYKD030 STEP010(再処理時)      *
      *  呼び出し先 : SYK003（明細金額チェックサム検証、静的CALL）      *
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  SYK001.
       AUTHOR.      SYK-SYSTEM-DEV.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT ORDIN     ASSIGN TO ORDIN
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-ORDIN-STATUS.
           SELECT ORDVALID  ASSIGN TO ORDVALID
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-ORDVALID-STATUS.
           SELECT ORDERR    ASSIGN TO ORDERR
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-ORDERR-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD  ORDIN
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
           COPY SYKCPY1 REPLACING LEADING ==SYK1== BY ==ORD1==.

       FD  ORDVALID
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
       01  VALID-REC                       PIC X(253).

       FD  ORDERR
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
       01  ERROR-REC.
           05  ERR-受注番号                PIC X(10).
           05  ERR-エラー内容              PIC X(40).
           05  FILLER                      PIC X(150).

       WORKING-STORAGE SECTION.
       01  WS-ファイル状態.
           05  WS-ORDIN-STATUS             PIC X(02).
           05  WS-ORDVALID-STATUS          PIC X(02).
           05  WS-ORDERR-STATUS            PIC X(02).

       01  WS-制御フラグ.
           05  WS-EOF-FLAG                 PIC X(01) VALUE 'N'.
               88  WS-EOF                      VALUE 'Y'.

       01  WS-作業項目.
           05  WS-IDX                      PIC S9(04)    COMP.
           05  WS-検証金額                 PIC S9(09)V99 COMP-3.
           05  WS-上限金額                 PIC S9(09)V99 COMP-3
                                            VALUE 10000000.00.
           05  WS-印字用金額               PIC 9(06).
           05  WS-合計チェック             PIC S9(09)V99 COMP-3
                                            VALUE ZERO.
           05  WS-エラー件数               PIC 9(05) VALUE ZERO.
           05  WS-処理件数                 PIC 9(05) VALUE ZERO.
           05  WS-チェック結果             PIC X(01).

       01  WS-エラーメッセージ             PIC X(40).

       PROCEDURE DIVISION.
       0000-メイン処理.
           PERFORM 1000-初期化処理
           PERFORM 2000-受注データ処理 UNTIL WS-EOF
           PERFORM 8000-終了処理
           STOP RUN.

       1000-初期化処理.
           OPEN INPUT  ORDIN
           OPEN OUTPUT ORDVALID
           OPEN OUTPUT ORDERR
           PERFORM 1100-受注データ読込.

       1100-受注データ読込.
           READ ORDIN INTO ORD1-受注レコード
               AT END
                   MOVE 'Y' TO WS-EOF-FLAG
           END-READ.

       2000-受注データ処理.
           ADD 1 TO WS-処理件数
           PERFORM 2100-明細検証
           PERFORM 2200-結果判定
           PERFORM 1100-受注データ読込.

       2100-明細検証.
           EVALUATE TRUE
               WHEN ORD1-新規登録
                   PERFORM 2110-新規登録検証
               WHEN ORD1-訂正
                   PERFORM 2120-訂正検証
               WHEN ORD1-取消
                   CONTINUE
               WHEN OTHER
                   MOVE '処理区分コード不正'  TO WS-エラーメッセージ
           END-EVALUATE.

       2110-新規登録検証.
           MOVE ORD1-受注金額合計 TO WS-検証金額
           CALL 'SYK003' USING ORD1-受注レコード WS-チェック結果
           MOVE ZERO TO WS-合計チェック
           PERFORM VARYING WS-IDX FROM 1 BY 1
                   UNTIL WS-IDX > ORD1-明細件数
               ADD ORD1-金額(WS-IDX) TO WS-合計チェック
           END-PERFORM.

       2120-訂正検証.
           MOVE ORD1-受注金額合計 TO WS-検証金額.

       2200-結果判定.
           IF WS-検証金額 > WS-上限金額
               MOVE '金額超過エラー'        TO WS-エラーメッセージ
               ADD 1 TO WS-エラー件数
               MOVE ORD1-受注番号           TO ERR-受注番号
               MOVE WS-エラーメッセージ     TO ERR-エラー内容
               WRITE ERROR-REC
           ELSE
               MOVE ORD1-受注金額合計       TO WS-印字用金額
               MOVE ORD1-受注レコード       TO VALID-REC
               WRITE VALID-REC
           END-IF.

       8000-終了処理.
           CLOSE ORDIN
           CLOSE ORDVALID
           CLOSE ORDERR
           DISPLAY 'SYK001 処理件数   = ' WS-処理件数
           DISPLAY 'SYK001 エラー件数 = ' WS-エラー件数.

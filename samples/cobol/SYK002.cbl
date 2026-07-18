      *================================================================*
      *  PROGRAM-ID : SYK002                                          *
      *  機能       : 受注データ登録バッチ（日次）                    *
      *  処理概要   : SYK001が出力した正常受注ファイル(ORDVALID)を     *
      *               読み込み、VSAM KSDS受注マスタ(ORDMSTR)へ新規     *
      *               登録または更新登録を行う。                       *
      *  起動元     : SYKD010 STEP020 / SYKD030 STEP020(再処理時)      *
      *  呼び出し先 : SYK004（在庫引当判定、動的CALL）                  *
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  SYK002.
       AUTHOR.      SYK-SYSTEM-DEV.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT ORDVALID  ASSIGN TO ORDVALID
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-ORDVALID-STATUS.
           SELECT ORDMSTR   ASSIGN TO ORDMSTR
                  ORGANIZATION IS INDEXED
                  ACCESS MODE IS DYNAMIC
                  RECORD KEY IS SYK2-受注番号
                  FILE STATUS IS WS-MASTER-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD  ORDVALID
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
           COPY SYKCPY1 REPLACING ==SYK1-== BY ==IN1-==.

       FD  ORDMSTR
           LABEL RECORDS ARE STANDARD.
           COPY SYKCPY2.

       WORKING-STORAGE SECTION.
       01  WS-ファイル状態.
           05  WS-ORDVALID-STATUS          PIC X(02).
           05  WS-MASTER-STATUS            PIC X(02).

       01  WS-制御フラグ.
           05  WS-EOF-FLAG                 PIC X(01) VALUE 'N'.
               88  WS-EOF                      VALUE 'Y'.
           05  WS-マスタ有無               PIC X(01).
               88  WS-マスタ該当あり           VALUE 'Y'.
               88  WS-マスタ該当なし           VALUE 'N'.

       01  WS-作業項目.
           05  WS-PROG-NAME                PIC X(08).
           05  WS-引当可否                 PIC X(01).
           05  WS-要求数量                 PIC S9(05)    COMP-3.
           05  WS-金額集計エリア           PIC 9(05) VALUE ZERO.

       01  WS-件数集計.
           05  WS-新規件数                 PIC 9(05) VALUE ZERO.
           05  WS-更新件数                 PIC 9(05) VALUE ZERO.

       PROCEDURE DIVISION.
       0000-メイン処理.
           PERFORM 1000-初期化処理
           PERFORM 2000-受注データ読込
           PERFORM 3000-登録メイン UNTIL WS-EOF
           PERFORM 8000-終了処理
           STOP RUN.

       1000-初期化処理.
           OPEN INPUT ORDVALID
           OPEN I-O   ORDMSTR
           MOVE 'SYK004' TO WS-PROG-NAME.

       2000-受注データ読込.
           READ ORDVALID INTO IN1-受注レコード
               AT END
                   MOVE 'Y' TO WS-EOF-FLAG
           END-READ.

       3000-登録メイン.
           PERFORM 3010-マスタ検索
           IF WS-マスタ該当なし
               PERFORM 3020-新規登録処理
           ELSE
               PERFORM 3030-更新登録処理
           END-IF
           PERFORM 2000-受注データ読込.

       3010-マスタ検索.
           MOVE IN1-受注番号 TO SYK2-受注番号
           READ ORDMSTR
               INVALID KEY
                   MOVE 'N' TO WS-マスタ有無
               NOT INVALID KEY
                   MOVE 'Y' TO WS-マスタ有無
           END-READ
           IF WS-MASTER-STATUS = '93'
               PERFORM 9000-緊急再更新処理
           END-IF.

       3020-新規登録処理.
           MOVE IN1-受注番号           TO SYK2-受注番号
           MOVE IN1-得意先コード       TO SYK2-得意先コード
           MOVE IN1-受注日             TO SYK2-受注日
           MOVE IN1-受注金額合計       TO SYK2-受注金額合計
           MOVE 'N'                    TO SYK2-入金状況
           MOVE SPACES                 TO SYK2-登録日時
           MOVE SPACES                 TO SYK2-更新日時
           WRITE SYK2-受注マスタレコード
               INVALID KEY
                   DISPLAY 'SYK002 マスタ登録エラー ' SYK2-受注番号
           END-WRITE
           ADD 1 TO WS-新規件数
           MOVE IN1-数量(1)            TO WS-要求数量
           CALL WS-PROG-NAME USING IN1-商品コード(1)
                                    WS-要求数量
                                    WS-引当可否.

       3030-更新登録処理.
           MOVE SYK2-受注金額合計 TO WS-金額集計エリア
           PERFORM 4000-マスタ更新処理 THRU 4000-マスタ更新処理-EXIT
           ADD 1 TO WS-更新件数.

       9000-緊急再更新処理.
           DISPLAY 'SYK002 レコードロック検出のため再更新を実施'
           GO TO 4010-マスタ書込.

       4000-マスタ更新処理.
           MOVE IN1-受注金額合計 TO SYK2-受注金額合計.

       4010-マスタ書込.
           REWRITE SYK2-受注マスタレコード.

       4000-マスタ更新処理-EXIT.
           EXIT.

       8000-終了処理.
           CLOSE ORDVALID
           CLOSE ORDMSTR
           DISPLAY 'SYK002 新規件数 = ' WS-新規件数
           DISPLAY 'SYK002 更新件数 = ' WS-更新件数.

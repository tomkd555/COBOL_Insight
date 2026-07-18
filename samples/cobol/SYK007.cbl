      *================================================================*
      *  PROGRAM-ID : SYK007                                          *
      *  機能       : 在庫引当率算出・引当数量確定バッチ（Db2、日次）  *
      *  処理概要   : SYK006が出力した在庫抽出ファイル(STKEXTR)を読み   *
      *               込み、倉庫マスタを参照して引当率を算出し、Db2の   *
      *               在庫マスタ表(SYKDB.ZAIKOM)の引当数量を確定する。   *
      *  起動元     : SYKD020 STEP020（インストリームPROC SYKPRC01）    *
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  SYK007.
       AUTHOR.      SYK-SYSTEM-DEV.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT STKEXTR   ASSIGN TO STKEXTR
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-STKEXTR-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD  STKEXTR
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
           COPY SYKCPY3.

       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  HOST-商品コード                 PIC X(08).
       01  HOST-倉庫コード                 PIC X(04).
       01  HOST-倉庫名                     PIC X(20).
       01  HOST-引当数量                   PIC S9(07)    COMP-3.
           EXEC SQL END DECLARE SECTION END-EXEC.

       01  WS-ファイル状態.
           05  WS-STKEXTR-STATUS           PIC X(02).

       01  WS-制御フラグ.
           05  WS-EOF-FLAG                 PIC X(01) VALUE 'N'.
               88  WS-EOF                      VALUE 'Y'.

       01  WS-作業項目.
           05  WS-処理件数                 PIC 9(05) VALUE ZERO.
           05  WS-引当率                   PIC S9(03)V99 COMP-3.

       PROCEDURE DIVISION.
       0000-メイン処理.
           PERFORM 1000-初期化処理
           PERFORM 2000-在庫照会処理 UNTIL WS-EOF
           PERFORM 8000-終了処理
           STOP RUN.

       1000-初期化処理.
           OPEN INPUT STKEXTR
           PERFORM 1100-抽出データ読込.

       1100-抽出データ読込.
           READ STKEXTR
               AT END
                   MOVE 'Y' TO WS-EOF-FLAG
           END-READ.

       2000-在庫照会処理.
           ADD 1 TO WS-処理件数
           MOVE SYK3-商品コード TO HOST-商品コード
           MOVE SYK3-倉庫コード TO HOST-倉庫コード
           EXEC SQL
               SELECT SOKO_NM INTO :HOST-倉庫名
                 FROM SYKDB.SOKOM
                WHERE SOKO_CD = :HOST-倉庫コード
           END-EXEC
           PERFORM 2100-引当率計算
           PERFORM 2200-引当数量更新
           PERFORM 1100-抽出データ読込.

       2100-引当率計算.
           COMPUTE WS-引当率 =
               (SYK3-引当可能数量 * 100) / SYK3-在庫数量.

       2200-引当数量更新.
           MOVE SYK3-引当可能数量 TO HOST-引当数量
           EXEC SQL
               UPDATE SYKDB.ZAIKOM
                  SET HIKIATE_SU = :HOST-引当数量
                WHERE SHOHIN_CD = :HOST-商品コード
                  AND SOKO_CD   = :HOST-倉庫コード
           END-EXEC.

       8000-終了処理.
           CLOSE STKEXTR
           DISPLAY 'SYK007 処理件数 = ' WS-処理件数.

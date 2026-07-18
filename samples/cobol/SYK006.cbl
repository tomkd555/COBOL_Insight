      *================================================================*
      *  PROGRAM-ID : SYK006                                          *
      *  機能       : 在庫マスタ更新バッチ（Db2、日次）                *
      *  処理概要   : 在庫日次トランザクション(STKIN)を読み込み、Db2の  *
      *               在庫マスタ表(SYKDB.ZAIKOM)を更新する。更新後の    *
      *               在庫マスタをカーソルで抽出しSTKEXTRへ出力する。    *
      *  起動元     : SYKD020 STEP010                                  *
      *  呼び出し先 : SYK005（メッセージ編集、静的CALL）                 *
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  SYK006.
       AUTHOR.      SYK-SYSTEM-DEV.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT STKIN     ASSIGN TO STKIN
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-STKIN-STATUS.
           SELECT STKEXTR   ASSIGN TO STKEXTR
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-STKEXTR-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD  STKIN
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
       01  STKIN-レコード.
           05  STKIN-商品コード            PIC X(08).
           05  STKIN-倉庫コード            PIC X(04).
           05  STKIN-増減数量              PIC S9(07)    COMP-3.
           05  STKIN-更新区分              PIC X(01).
               88  STKIN-増加                  VALUE '1'.
               88  STKIN-減少                  VALUE '2'.

       FD  STKEXTR
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
           COPY SYKCPY3.

       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  HOST-商品コード                 PIC X(08).
       01  HOST-倉庫コード                 PIC X(04).
       01  HOST-在庫数量                   PIC S9(07)    COMP-3.
       01  HOST-引当数量                   PIC S9(07)    COMP-3.
       01  HOST-増減数量                   PIC S9(07)    COMP-3.
           EXEC SQL END DECLARE SECTION END-EXEC.

       01  WS-ファイル状態.
           05  WS-STKIN-STATUS             PIC X(02).
           05  WS-STKEXTR-STATUS           PIC X(02).

       01  WS-制御フラグ.
           05  WS-EOF-FLAG                 PIC X(01) VALUE 'N'.
               88  WS-EOF                      VALUE 'Y'.

       01  WS-作業項目.
           05  WS-処理件数                 PIC 9(05) VALUE ZERO.
           05  WS-エラー件数               PIC 9(05) VALUE ZERO.
           05  WS-エラー件数INDEX          PIC S9(04)    COMP
                                            VALUE ZERO.
           05  WS-メッセージ区分           PIC X(02).
           05  WS-メッセージ内容           PIC X(80).

       01  WS-エラー商品テーブル.
           05  WS-エラー商品 OCCURS 20 TIMES
                                            PIC X(08).

       PROCEDURE DIVISION.
       0000-メイン処理.
           PERFORM 1000-初期化処理
           PERFORM 2000-在庫更新処理 UNTIL WS-EOF
           PERFORM 3000-抽出ファイル出力
           PERFORM 8000-終了処理
           STOP RUN.

       1000-初期化処理.
           OPEN INPUT  STKIN
           OPEN OUTPUT STKEXTR
           PERFORM 1100-トランザクション読込.

       1100-トランザクション読込.
           READ STKIN INTO STKIN-レコード
               AT END
                   MOVE 'Y' TO WS-EOF-FLAG
           END-READ.

       2000-在庫更新処理.
           ADD 1 TO WS-処理件数
           MOVE STKIN-商品コード TO HOST-商品コード
           MOVE STKIN-倉庫コード TO HOST-倉庫コード
           EXEC SQL
               SELECT ZAIKO_SU INTO :HOST-在庫数量
                 FROM SYKDB.ZAIKOM
                WHERE SHOHIN_CD = :HOST-商品コード
                  AND SOKO_CD   = :HOST-倉庫コード
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   PERFORM 2100-在庫数更新
               WHEN 100
                   PERFORM 2200-在庫マスタ新規登録
               WHEN OTHER
                   PERFORM 2900-エラー商品登録
           END-EVALUATE
           PERFORM 1100-トランザクション読込.

       2100-在庫数更新.
           MOVE STKIN-増減数量 TO HOST-増減数量
           EXEC SQL
               UPDATE SYKDB.ZAIKOM
                  SET ZAIKO_SU = ZAIKO_SU + :HOST-増減数量
                WHERE SHOHIN_CD = :HOST-商品コード
                  AND SOKO_CD   = :HOST-倉庫コード
           END-EXEC.

       2200-在庫マスタ新規登録.
           MOVE STKIN-増減数量 TO HOST-増減数量
           EXEC SQL
               INSERT INTO SYKDB.ZAIKOM
                      (SHOHIN_CD, SOKO_CD, ZAIKO_SU,
                       HIKIATE_SU, KOSHIN_BI)
               VALUES (:HOST-商品コード, :HOST-倉庫コード,
                       :HOST-増減数量, 0, '00000000')
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'E1' TO WS-メッセージ区分
               MOVE 'ZAIKO INSERT ERROR' TO WS-メッセージ内容
               CALL 'SYK005' USING WS-メッセージ区分 WS-メッセージ内容
           END-IF.

       2900-エラー商品登録.
           ADD 1 TO WS-エラー件数
           ADD 1 TO WS-エラー件数INDEX
           MOVE STKIN-商品コード TO WS-エラー商品(WS-エラー件数INDEX)
           MOVE 'E1' TO WS-メッセージ区分
           MOVE 'ZAIKO SELECT ERROR' TO WS-メッセージ内容
           CALL 'SYK005' USING WS-メッセージ区分 WS-メッセージ内容.

       3000-抽出ファイル出力.
           EXEC SQL
               DECLARE SYKZAIKOCUR CURSOR FOR
                   SELECT SHOHIN_CD, SOKO_CD, ZAIKO_SU, HIKIATE_SU
                     FROM SYKDB.ZAIKOM
           END-EXEC
           EXEC SQL
               OPEN SYKZAIKOCUR
           END-EXEC
           PERFORM 3100-抽出データフェッチ
               UNTIL SQLCODE = 100
           EXEC SQL
               CLOSE SYKZAIKOCUR
           END-EXEC.

       3100-抽出データフェッチ.
           EXEC SQL
               FETCH SYKZAIKOCUR
                   INTO :HOST-商品コード, :HOST-倉庫コード,
                        :HOST-在庫数量, :HOST-引当数量
           END-EXEC
           IF SQLCODE = 0
               MOVE HOST-商品コード TO SYK3-商品コード
               MOVE HOST-倉庫コード TO SYK3-倉庫コード
               MOVE HOST-在庫数量   TO SYK3-在庫数量
               MOVE HOST-引当数量   TO SYK3-引当可能数量
               MOVE '1'             TO SYK3-更新区分
               MOVE FUNCTION CURRENT-DATE(1:8) TO SYK3-処理日
               WRITE SYK3-在庫抽出レコード
           END-IF.

       8000-終了処理.
           CLOSE STKIN
           CLOSE STKEXTR
           DISPLAY 'SYK006 処理件数   = ' WS-処理件数
           DISPLAY 'SYK006 エラー件数 = ' WS-エラー件数.

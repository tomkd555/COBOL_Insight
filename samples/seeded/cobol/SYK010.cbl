      *================================================================*
      *  PROGRAM-ID : SYK010                                          *
      *  機能       : 在庫集計表の照会・更新バッチ（Db2、日次）        *
      *  処理概要   : Db2の在庫集計表(SYKDB.ZAIKOSHUKEI)を照会し、      *
      *               引当数量を更新して集計行を追加する。処理結果は   *
      *               SYKLOG(処理ログ)へ書き出す。                    *
      *  起動元     : SYKD040 STEP010                                 *
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  SYK010.
       AUTHOR.      SYK-SYSTEM-DEV.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT SYKLOG    ASSIGN TO SYKLOG
                  ORGANIZATION IS SEQUENTIAL.

       DATA DIVISION.
       FILE SECTION.
       FD  SYKLOG
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
       01  SYK4-ログレコード.
           05  SYK4-商品コード             PIC X(08).
           05  SYK4-在庫数量               PIC S9(07).

       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL DECLARE SYKDB.ZAIKOSHUKEI TABLE
           ( SHUKEI_YM                      CHAR(6) NOT NULL,
             SHOHIN_CD                      CHAR(8) NOT NULL,
             SOKO_CD                        CHAR(4) NOT NULL,
             ZAIKO_SU                       DECIMAL(7, 0) NOT NULL,
             HIKIATE_SU                     DECIMAL(7, 0) NOT NULL
           ) END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  HOST-集計年月                   PIC X(06) VALUE '250718'.
       01  HOST-商品コード                 PIC X(08) VALUE SPACE.
       01  HOST-倉庫コード                 PIC X(04) VALUE SPACE.
       01  HOST-在庫数量                   PIC S9(07)    COMP-3
                                                     VALUE ZERO.
       01  HOST-引当数量                   PIC S9(07)    COMP-3
                                                     VALUE ZERO.
       01  HOST-更新行数                   PIC S9(09)    COMP VALUE ZERO.
           EXEC SQL END DECLARE SECTION END-EXEC.

       PROCEDURE DIVISION.
       0000-メイン処理.
           PERFORM 1000-初期化処理
           PERFORM 2000-在庫集計照会
           PERFORM 3000-引当数量更新
           PERFORM 4000-集計年月照会
           PERFORM 5000-集計行追加
           PERFORM 8000-終了処理
           STOP RUN.

       1000-初期化処理.
           OPEN OUTPUT SYKLOG
           MOVE '00000001' TO HOST-商品コード
           MOVE '0001'     TO HOST-倉庫コード.

       2000-在庫集計照会.
           EXEC SQL
               SELECT ZAIKO_SU INTO :HOST-在庫数量
                 FROM SYKDB.ZAIKOSHUKEI
           END-EXEC
           IF SQLCODE NOT = ZERO
               PERFORM 9000-SQL異常
           END-IF.

       3000-引当数量更新.
           MOVE HOST-在庫数量 TO HOST-引当数量
           EXEC SQL
               UPDATE SYKDB.ZAIKOSHUKEI
                  SET HIKIATE_SU = :HOST-引当数量
           END-EXEC
           IF SQLCODE NOT = ZERO
               PERFORM 9000-SQL異常
           END-IF.

       4000-集計年月照会.
           EXEC SQL
               SELECT SHUKEI_YMD INTO :HOST-集計年月
                 FROM SYKDB.ZAIKOSHUKEI
                WHERE SHOHIN_CD = :HOST-商品コード
           END-EXEC
           IF SQLCODE NOT = ZERO
               PERFORM 9000-SQL異常
           END-IF.

       5000-集計行追加.
           EXEC SQL
               INSERT INTO SYKDB.ZAIKOSHUKEI
                   VALUES (:HOST-集計年月, :HOST-商品コード,
                           :HOST-倉庫コード, :HOST-在庫数量,
                           :HOST-引当数量)
           END-EXEC
           EXEC SQL
               GET DIAGNOSTICS :HOST-更新行数 = ROW_COUNT
           END-EXEC.

       8000-終了処理.
           MOVE HOST-商品コード TO SYK4-商品コード
           MOVE HOST-在庫数量   TO SYK4-在庫数量
           WRITE SYK4-ログレコード
           CLOSE SYKLOG
           DISPLAY 'SYK010 集計年月 = ' HOST-集計年月
           DISPLAY 'SYK010 追加行数 = ' HOST-更新行数.

       9000-SQL異常.
           DISPLAY 'SYK010 SQL ERROR = ' SQLCODE.

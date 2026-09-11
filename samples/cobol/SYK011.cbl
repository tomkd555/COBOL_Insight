      *================================================================*
      *  PROGRAM-ID : SYK011                                          *
      *  機能       : 在庫集計表のカーソル処理バッチ（Db2、日次）      *
      *  処理概要   : Db2の在庫集計表(SYKDB.ZAIKOSHUKEI)をカーソルで    *
      *               読み、引当数量を位置づけ更新する。カーソルの     *
      *               操作順序に誤りを仕込んである。                  *
      *  起動元     : SYKD040 STEP020                                 *
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  SYK011.
       AUTHOR.      SYK-SYSTEM-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  HOST-商品コード                 PIC X(08) VALUE SPACE.
       01  HOST-倉庫コード                 PIC X(04) VALUE SPACE.
       01  HOST-引当数量                   PIC S9(07)    COMP-3
                                                     VALUE ZERO.
           EXEC SQL END DECLARE SECTION END-EXEC.

       01  WS-作業項目.
           05  WS-処理区分                 PIC X(01) VALUE '1'.
           05  WS-読込件数                 PIC 9(05) VALUE ZERO.
           05  WS-異常件数                 PIC 9(05) VALUE ZERO.

       PROCEDURE DIVISION.
       0000-メイン処理.
           PERFORM 2000-準備照会
           PERFORM 3000-再開照会
           PERFORM 4000-明細宣言
           PERFORM 5000-取消後照会
           PERFORM 6000-引当数量更新
           PERFORM 8000-終了処理
           STOP RUN.

       2000-準備照会.
           EXEC SQL
               DECLARE CSR-JUNBI CURSOR FOR
                   SELECT SHOHIN_CD
                     FROM SYKDB.ZAIKOSHUKEI
                    FOR READ ONLY
           END-EXEC
           IF WS-処理区分 = '1'
               EXEC SQL OPEN CSR-JUNBI END-EXEC
           END-IF
           EXEC SQL
               FETCH CSR-JUNBI INTO :HOST-商品コード
           END-EXEC
           IF SQLCODE = ZERO
               ADD 1 TO WS-読込件数
           END-IF
           EXEC SQL CLOSE CSR-JUNBI END-EXEC.

       3000-再開照会.
           EXEC SQL
               DECLARE CSR-SAIKAI CURSOR FOR
                   SELECT SOKO_CD
                     FROM SYKDB.ZAIKOSHUKEI
                    FOR READ ONLY
           END-EXEC
           EXEC SQL OPEN CSR-SAIKAI END-EXEC
           EXEC SQL
               FETCH CSR-SAIKAI INTO :HOST-倉庫コード
           END-EXEC
           IF SQLCODE = ZERO
               ADD 1 TO WS-読込件数
           END-IF
           EXEC SQL OPEN CSR-SAIKAI END-EXEC
           EXEC SQL CLOSE CSR-SAIKAI END-EXEC.

       4000-明細宣言.
           EXEC SQL
               DECLARE CSR-MEISAI CURSOR FOR
                   SELECT SHOHIN_CD
                     FROM SYKDB.ZAIKOSHUKEI
                    FOR READ ONLY
           END-EXEC.

       5000-取消後照会.
           EXEC SQL
               DECLARE CSR-TORIKESHI CURSOR WITH HOLD FOR
                   SELECT SHOHIN_CD
                     FROM SYKDB.ZAIKOSHUKEI
                    FOR READ ONLY
           END-EXEC
           EXEC SQL OPEN CSR-TORIKESHI END-EXEC
           EXEC SQL ROLLBACK END-EXEC
           EXEC SQL
               FETCH CSR-TORIKESHI INTO :HOST-商品コード
           END-EXEC
           IF SQLCODE = ZERO
               ADD 1 TO WS-読込件数
           END-IF
           EXEC SQL CLOSE CSR-TORIKESHI END-EXEC.

       6000-引当数量更新.
           EXEC SQL
               DECLARE CSR-KOSHIN CURSOR FOR
                   SELECT HIKIATE_SU
                     FROM SYKDB.ZAIKOSHUKEI
                    FOR READ ONLY
           END-EXEC
           EXEC SQL OPEN CSR-KOSHIN END-EXEC
           EXEC SQL
               FETCH CSR-KOSHIN INTO :HOST-引当数量
           END-EXEC
           IF SQLCODE = ZERO
               EXEC SQL
                   UPDATE SYKDB.ZAIKOSHUKEI
                      SET HIKIATE_SU = :HOST-引当数量
                    WHERE CURRENT OF CSR-KOSHIN
               END-EXEC
               IF SQLCODE NOT = ZERO
                   ADD 1 TO WS-異常件数
               END-IF
           END-IF
           EXEC SQL CLOSE CSR-KOSHIN END-EXEC.

       8000-終了処理.
           DISPLAY 'SYK011 読込件数 = ' WS-読込件数
           DISPLAY 'SYK011 異常件数 = ' WS-異常件数.

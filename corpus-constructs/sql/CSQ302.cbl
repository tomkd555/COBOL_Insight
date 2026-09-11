      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ302                                     *
      *  Constructs : UPDATE ... SET ... WHERE; DELETE ... WHERE; *
      *               positioned UPDATE and positioned DELETE,    *
      *               both WHERE CURRENT OF, on one FOR UPDATE OF *
      *               cursor.                                     *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ302.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL INCLUDE CSD301 END-EXEC.

           EXEC SQL
               DECLARE CSQDB.HINMOKU_LOG TABLE
               ( HINMOKU_CD              CHAR(8) NOT NULL,
                 HINMOKU_MEI             VARCHAR(40) NOT NULL,
                 TANKA                  DECIMAL(9, 2) NOT NULL,
                 KOSHIN_YMD             CHAR(8) )
           END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-HINMOKU-CD                   PIC X(8).
       01  WS-NEW-TANKA                   PIC S9(7)V9(2) COMP-3.
       01  WS-DATE                        PIC X(8).
       01  WS-DATE-LIMIT                  PIC X(8).
       01  WS-ZAIKO-LIMIT                 PIC S9(9) COMP.
       01  WS-CUR-CD                      PIC X(8).
       01  WS-CUR-TANKA                   PIC S9(7)V9(2) COMP-3.
       01  WS-CUR-ZAIKO                   PIC S9(9) COMP.
           EXEC SQL END DECLARE SECTION END-EXEC.

           EXEC SQL
               DECLARE CSR-HINMOKU CURSOR FOR
                   SELECT HINMOKU_CD, TANKA, ZAIKO_SU
                     FROM CSQDB.HINMOKU
                    WHERE ZAIKO_SU < :WS-ZAIKO-LIMIT
                    FOR UPDATE OF TANKA, ZAIKO_SU
           END-EXEC.

       01  WS-ERR-MSG                     PIC X(60).

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-UPDATE-BY-PK
           PERFORM 2000-DELETE-OLD-LOG
           PERFORM 3000-CURSOR-UPD-DEL
           EXEC SQL COMMIT END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'コミットエラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           DISPLAY 'CSQ302 END'
           STOP RUN.

       1000-UPDATE-BY-PK.
           MOVE 'SH000001' TO WS-HINMOKU-CD
           MOVE 1800.00    TO WS-NEW-TANKA
           MOVE '20260910' TO WS-DATE
           EXEC SQL
               UPDATE CSQDB.HINMOKU
                  SET TANKA = :WS-NEW-TANKA,
                      KOSHIN_YMD = :WS-DATE
                WHERE HINMOKU_CD = :WS-HINMOKU-CD
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '単価更新エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       2000-DELETE-OLD-LOG.
           MOVE 'SH000099' TO WS-HINMOKU-CD
           MOVE '20250101' TO WS-DATE-LIMIT
           EXEC SQL
               DELETE FROM CSQDB.HINMOKU_LOG
                WHERE HINMOKU_CD = :WS-HINMOKU-CD
                  AND KOSHIN_YMD < :WS-DATE-LIMIT
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '履歴削除エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       3000-CURSOR-UPD-DEL.
           MOVE 5 TO WS-ZAIKO-LIMIT
           EXEC SQL
               OPEN CSR-HINMOKU
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'カーソル開始エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 3100-FETCH-LOOP UNTIL SQLCODE NOT = 0
           EXEC SQL
               CLOSE CSR-HINMOKU
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'カーソル終了エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       3100-FETCH-LOOP.
           EXEC SQL
               FETCH CSR-HINMOKU
                INTO :WS-CUR-CD, :WS-CUR-TANKA, :WS-CUR-ZAIKO
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   PERFORM 3200-UPD-OR-DEL
               WHEN 100
                   CONTINUE
               WHEN OTHER
                   MOVE 'フェッチエラー' TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       3200-UPD-OR-DEL.
           IF WS-CUR-ZAIKO = 0
               EXEC SQL
                   DELETE FROM CSQDB.HINMOKU
                    WHERE CURRENT OF CSR-HINMOKU
               END-EXEC
               IF SQLCODE NOT = 0
                   MOVE '在庫ゼロ削除エラー' TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
               END-IF
           ELSE
               COMPUTE WS-NEW-TANKA = WS-CUR-TANKA * 1.05
                   ON SIZE ERROR
                       MOVE '単価計算エラー' TO WS-ERR-MSG
                       PERFORM 9900-SQL-ERROR
               END-COMPUTE
               EXEC SQL
                   UPDATE CSQDB.HINMOKU
                      SET TANKA = :WS-NEW-TANKA
                    WHERE CURRENT OF CSR-HINMOKU
               END-EXEC
               IF SQLCODE NOT = 0
                   MOVE '位置更新エラー' TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
               END-IF
           END-IF.

       9900-SQL-ERROR.
           DISPLAY 'CSQ302 SQLエラー SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-ERR-MSG
           EXEC SQL ROLLBACK END-EXEC
           MOVE 12 TO RETURN-CODE
           GOBACK.

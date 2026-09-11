      *----------------------------------------------------------------*
      * PROGRAM-ID : CSQ206
      * Constructs : DECLARE CURSOR ... FOR READ ONLY WITH UR, on a
      *   SELECT with ORDER BY; DECLARE CURSOR SENSITIVE STATIC
      *   SCROLL, FOR UPDATE OF one column, WITH CS, SKIP LOCKED
      *   DATA, with a positioned UPDATE.
      *----------------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    CSQ206.
       AUTHOR.        CSQ-CONSTRUCTS-DEV.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       SOURCE-COMPUTER. IBM-370.
       OBJECT-COMPUTER. IBM-370.

       DATA DIVISION.
       WORKING-STORAGE SECTION.

           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL INCLUDE CSD201 END-EXEC.

       01  WS-COUNT-UR                 PIC 9(05) VALUE ZERO.
       01  WS-COUNT-CS                 PIC 9(05) VALUE ZERO.

       PROCEDURE DIVISION.

       0000-MAIN.
           PERFORM 1000-UR-CURSOR
           PERFORM 2000-CS-CURSOR
           PERFORM 8000-END
           STOP RUN.

      *----- 未確定行も読む WITH UR の参照専用カーソルです -----
       1000-UR-CURSOR.
           EXEC SQL
               DECLARE CSR-UR CURSOR FOR
                   SELECT SHOHIN_CD, SHOHIN_MEI
                     FROM CSQDB.SHOHIN
                    ORDER BY SHOHIN_MEI
                    FOR READ ONLY
                    WITH UR
           END-EXEC
           EXEC SQL OPEN CSR-UR END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-SQL-ERROR
           END-IF
           PERFORM 1100-UR-FETCH-LOOP UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-UR END-EXEC.

       1100-UR-FETCH-LOOP.
           EXEC SQL
               FETCH CSR-UR INTO :SHOHIN-CD, :SHOHIN-MEI
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-COUNT-UR
               WHEN 100
                   CONTINUE
               WHEN OTHER
                   PERFORM 9000-SQL-ERROR
           END-EVALUATE.

      *----- ロックされた行を飛ばして更新対象を選びます -----
       2000-CS-CURSOR.
           EXEC SQL
               DECLARE CSR-CS SENSITIVE STATIC SCROLL CURSOR FOR
                   SELECT SHOHIN_CD, ZAIKO_SU
                     FROM CSQDB.SHOHIN
                    FOR UPDATE OF ZAIKO_SU
                    WITH CS
                    SKIP LOCKED DATA
           END-EXEC
           EXEC SQL OPEN CSR-CS END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-SQL-ERROR
           END-IF
           EXEC SQL
               FETCH FIRST FROM CSR-CS
                INTO :SHOHIN-CD, :ZAIKO-SU
           END-EXEC
           IF SQLCODE NOT = 0 AND SQLCODE NOT = 100
               PERFORM 9000-SQL-ERROR
           END-IF
           IF SQLCODE = 0
               PERFORM 2100-CS-UPDATE
           END-IF
           EXEC SQL CLOSE CSR-CS END-EXEC.

       2100-CS-UPDATE.
           ADD 1 TO WS-COUNT-CS
           EXEC SQL
               UPDATE CSQDB.SHOHIN
                  SET ZAIKO_SU = ZAIKO_SU - 1
                WHERE CURRENT OF CSR-CS
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-SQL-ERROR
           END-IF.

       8000-END.
           EXEC SQL COMMIT END-EXEC
           DISPLAY 'CSQ206 UR 件数     = ' WS-COUNT-UR
           DISPLAY 'CSQ206 CS 更新件数 = ' WS-COUNT-CS
           MOVE ZERO TO RETURN-CODE.

       9000-SQL-ERROR.
           DISPLAY 'CSQ206 SQL エラー SQLCODE = ' SQLCODE
           EXEC SQL CLOSE CSR-UR END-EXEC
           EXEC SQL CLOSE CSR-CS END-EXEC
           EXEC SQL ROLLBACK END-EXEC
           MOVE 12 TO RETURN-CODE
           GOBACK.

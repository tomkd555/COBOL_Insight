      *----------------------------------------------------------------*
      * PROGRAM-ID : CSQ203
      * Constructs : DECLARE CURSOR WITH HOLD, fetched across a
      *   COMMIT taken every N rows; DECLARE CURSOR WITHOUT HOLD,
      *   fetched with no COMMIT inside its own loop; FOR READ ONLY
      *   next to FOR FETCH ONLY.
      *----------------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    CSQ203.
       AUTHOR.        CSQ-CONSTRUCTS-DEV.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       SOURCE-COMPUTER. IBM-370.
       OBJECT-COMPUTER. IBM-370.

       DATA DIVISION.
       WORKING-STORAGE SECTION.

           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL INCLUDE CSD201 END-EXEC.

       01  WS-COMMIT-CNT              PIC S9(04) COMP VALUE ZERO.
       01  WS-COMMIT-MAX              PIC S9(04) COMP VALUE 100.
       01  WS-READ-CNT-A              PIC 9(05) VALUE ZERO.
       01  WS-READ-CNT-B              PIC 9(05) VALUE ZERO.

       PROCEDURE DIVISION.

       0000-MAIN.
           PERFORM 1000-HOLD-CURSOR
           PERFORM 2000-NOHOLD-CURSOR
           PERFORM 8000-END
           STOP RUN.

      *----- WITH HOLD: COMMIT の後も次の FETCH が続けられます -----
       1000-HOLD-CURSOR.
           EXEC SQL
               DECLARE CSR-HOLD CURSOR WITH HOLD FOR
                   SELECT SHOHIN_CD, SHOHIN_MEI
                     FROM CSQDB.SHOHIN
                    ORDER BY SHOHIN_CD
                    FOR READ ONLY
           END-EXEC
           EXEC SQL OPEN CSR-HOLD END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-SQL-ERROR
           END-IF
           PERFORM 1100-HOLD-FETCH-LOOP UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-HOLD END-EXEC.

       1100-HOLD-FETCH-LOOP.
           EXEC SQL
               FETCH CSR-HOLD INTO :SHOHIN-CD, :SHOHIN-MEI
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-READ-CNT-A
                   PERFORM 1200-HOLD-COMMIT-CHECK
               WHEN 100
                   CONTINUE
               WHEN OTHER
                   PERFORM 9000-SQL-ERROR
           END-EVALUATE.

       1200-HOLD-COMMIT-CHECK.
           ADD 1 TO WS-COMMIT-CNT
           IF WS-COMMIT-CNT >= WS-COMMIT-MAX
               EXEC SQL COMMIT END-EXEC
               MOVE ZERO TO WS-COMMIT-CNT
           END-IF.

      *----- WITHOUT HOLD: このループの中では COMMIT しません -----
       2000-NOHOLD-CURSOR.
           EXEC SQL
               DECLARE CSR-NOHOLD CURSOR WITHOUT HOLD FOR
                   SELECT SHOHIN_CD, TANKA
                     FROM CSQDB.SHOHIN
                    FOR FETCH ONLY
           END-EXEC
           EXEC SQL OPEN CSR-NOHOLD END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-SQL-ERROR
           END-IF
           PERFORM 2100-NOHOLD-FETCH-LOOP UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-NOHOLD END-EXEC.

       2100-NOHOLD-FETCH-LOOP.
           EXEC SQL
               FETCH CSR-NOHOLD INTO :SHOHIN-CD, :TANKA
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-READ-CNT-B
               WHEN 100
                   CONTINUE
               WHEN OTHER
                   PERFORM 9000-SQL-ERROR
           END-EVALUATE.

       8000-END.
           EXEC SQL COMMIT END-EXEC
           DISPLAY 'CSQ203 WITH HOLD 件数    = ' WS-READ-CNT-A
           DISPLAY 'CSQ203 WITHOUT HOLD 件数 = ' WS-READ-CNT-B
           MOVE ZERO TO RETURN-CODE.

       9000-SQL-ERROR.
           DISPLAY 'CSQ203 SQL エラー SQLCODE = ' SQLCODE
           EXEC SQL CLOSE CSR-HOLD END-EXEC
           EXEC SQL CLOSE CSR-NOHOLD END-EXEC
           EXEC SQL ROLLBACK END-EXEC
           MOVE 12 TO RETURN-CODE
           GOBACK.

      *----------------------------------------------------------------*
      * PROGRAM-ID : CSQ205
      * Constructs : DECLARE CURSOR INSENSITIVE SCROLL, WITH ROWSET
      *   POSITIONING; FETCH FIRST, FETCH LAST, FETCH ABSOLUTE n,
      *   FETCH PRIOR, FETCH RELATIVE n, each FROM the scroll cursor;
      *   FETCH NEXT ROWSET FROM cursor FOR n ROWS INTO host tables.
      *----------------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    CSQ205.
       AUTHOR.        CSQ-CONSTRUCTS-DEV.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       SOURCE-COMPUTER. IBM-370.
       OBJECT-COMPUTER. IBM-370.

       DATA DIVISION.
       WORKING-STORAGE SECTION.

           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL INCLUDE CSD202 END-EXEC.

       01  WS-COUNT                   PIC 9(05) VALUE ZERO.
       01  WS-ROWSET-CNT              PIC 9(05) VALUE ZERO.

       01  WS-CHUMON-TBL.
           05  WS-TBL-NO              PIC X(10) OCCURS 10 TIMES.
           05  WS-TBL-SHOHIN          PIC X(08) OCCURS 10 TIMES.
           05  WS-TBL-SURYO           PIC S9(09) USAGE COMP
                                       OCCURS 10 TIMES.

       PROCEDURE DIVISION.

       0000-MAIN.
           PERFORM 1000-SCROLL-CURSOR
           PERFORM 8000-END
           STOP RUN.

       1000-SCROLL-CURSOR.
           EXEC SQL
               DECLARE CSR-SCR INSENSITIVE SCROLL CURSOR
                   WITH ROWSET POSITIONING FOR
                   SELECT CHUMON_NO, SHOHIN_CD, SURYO
                     FROM CSQDB.CHUMON
                    ORDER BY CHUMON_NO
                    FOR READ ONLY
           END-EXEC
           EXEC SQL OPEN CSR-SCR END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-SQL-ERROR
           END-IF

           EXEC SQL
               FETCH FIRST FROM CSR-SCR
                INTO :CHUMON-NO, :SHOHIN-CD, :SURYO
           END-EXEC
           IF SQLCODE NOT = 0 AND SQLCODE NOT = 100
               PERFORM 9000-SQL-ERROR
           END-IF
           IF SQLCODE = 0
               ADD 1 TO WS-COUNT
               DISPLAY 'FIRST    : ' CHUMON-NO
           END-IF

           EXEC SQL
               FETCH LAST FROM CSR-SCR
                INTO :CHUMON-NO, :SHOHIN-CD, :SURYO
           END-EXEC
           IF SQLCODE NOT = 0 AND SQLCODE NOT = 100
               PERFORM 9000-SQL-ERROR
           END-IF
           IF SQLCODE = 0
               ADD 1 TO WS-COUNT
               DISPLAY 'LAST     : ' CHUMON-NO
           END-IF

           EXEC SQL
               FETCH ABSOLUTE 2 FROM CSR-SCR
                INTO :CHUMON-NO, :SHOHIN-CD, :SURYO
           END-EXEC
           IF SQLCODE NOT = 0 AND SQLCODE NOT = 100
               PERFORM 9000-SQL-ERROR
           END-IF
           IF SQLCODE = 0
               ADD 1 TO WS-COUNT
               DISPLAY 'ABSOLUTE : ' CHUMON-NO
           END-IF

           EXEC SQL
               FETCH PRIOR FROM CSR-SCR
                INTO :CHUMON-NO, :SHOHIN-CD, :SURYO
           END-EXEC
           IF SQLCODE NOT = 0 AND SQLCODE NOT = 100
               PERFORM 9000-SQL-ERROR
           END-IF
           IF SQLCODE = 0
               ADD 1 TO WS-COUNT
               DISPLAY 'PRIOR    : ' CHUMON-NO
           END-IF

           EXEC SQL
               FETCH RELATIVE 1 FROM CSR-SCR
                INTO :CHUMON-NO, :SHOHIN-CD, :SURYO
           END-EXEC
           IF SQLCODE NOT = 0 AND SQLCODE NOT = 100
               PERFORM 9000-SQL-ERROR
           END-IF
           IF SQLCODE = 0
               ADD 1 TO WS-COUNT
               DISPLAY 'RELATIVE : ' CHUMON-NO
           END-IF

           EXEC SQL
               FETCH NEXT ROWSET FROM CSR-SCR
                FOR 10 ROWS
                INTO :WS-TBL-NO, :WS-TBL-SHOHIN, :WS-TBL-SURYO
           END-EXEC
           IF SQLCODE NOT = 0 AND SQLCODE NOT = 100
               PERFORM 9000-SQL-ERROR
           END-IF
           IF SQLCODE = 0 OR SQLCODE = 100
               MOVE SQLERRD(3) TO WS-ROWSET-CNT
               ADD WS-ROWSET-CNT TO WS-COUNT
               DISPLAY 'ROWSET   : ' WS-ROWSET-CNT ' 件'
           END-IF

           EXEC SQL CLOSE CSR-SCR END-EXEC.

       8000-END.
           DISPLAY 'CSQ205 取得件数 = ' WS-COUNT
           MOVE ZERO TO RETURN-CODE.

       9000-SQL-ERROR.
           DISPLAY 'CSQ205 SQL エラー SQLCODE = ' SQLCODE
           EXEC SQL CLOSE CSR-SCR END-EXEC
           MOVE 12 TO RETURN-CODE
           GOBACK.

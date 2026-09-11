      *----------------------------------------------------------------*
      * PROGRAM-ID : CSQ207
      * Constructs : DECLARE CURSOR ... FOR FETCH ONLY WITH RS,
      *   OPTIMIZE FOR n ROWS; DECLARE CURSOR on a SELECT that carries
      *   QUERYNO, FETCH FIRST n ROWS ONLY, FOR READ ONLY WITH RR.
      *----------------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    CSQ207.
       AUTHOR.        CSQ-CONSTRUCTS-DEV.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       SOURCE-COMPUTER. IBM-370.
       OBJECT-COMPUTER. IBM-370.

       DATA DIVISION.
       WORKING-STORAGE SECTION.

           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL INCLUDE CSD201 END-EXEC.

       01  WS-COUNT-RS                 PIC 9(05) VALUE ZERO.
       01  WS-COUNT-RR                 PIC 9(05) VALUE ZERO.

       PROCEDURE DIVISION.

       0000-MAIN.
           PERFORM 1000-RS-CURSOR
           PERFORM 2000-RR-CURSOR
           PERFORM 8000-END
           STOP RUN.

      *----- 大量件数を見込んで最適化ヒントを渡すカーソル -----
       1000-RS-CURSOR.
           EXEC SQL
               DECLARE CSR-RSV CURSOR FOR
                   SELECT SHOHIN_CD, SHOHIN_MEI, ZAIKO_SU
                     FROM CSQDB.SHOHIN
                    WHERE ZAIKO_SU > 0
                    FOR FETCH ONLY
                    OPTIMIZE FOR 25 ROWS
                    WITH RS
           END-EXEC
           EXEC SQL OPEN CSR-RSV END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-SQL-ERROR
           END-IF
           PERFORM 1100-RS-FETCH-LOOP UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-RSV END-EXEC.

       1100-RS-FETCH-LOOP.
           EXEC SQL
               FETCH CSR-RSV
                INTO :SHOHIN-CD, :SHOHIN-MEI, :ZAIKO-SU
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-COUNT-RS
               WHEN 100
                   CONTINUE
               WHEN OTHER
                   PERFORM 9000-SQL-ERROR
           END-EVALUATE.

      *----- 単価上位 10 件を強い一貫性で取り出します -----
       2000-RR-CURSOR.
           EXEC SQL
               DECLARE CSR-RR CURSOR FOR
                   SELECT SHOHIN_CD, SHOHIN_MEI, TANKA
                     FROM CSQDB.SHOHIN
                    ORDER BY TANKA DESC
                    FETCH FIRST 10 ROWS ONLY
                    FOR READ ONLY
                    WITH RR
                    QUERYNO 100
           END-EXEC
           EXEC SQL OPEN CSR-RR END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-SQL-ERROR
           END-IF
           PERFORM 2100-RR-FETCH-LOOP UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-RR END-EXEC.

       2100-RR-FETCH-LOOP.
           EXEC SQL
               FETCH CSR-RR
                INTO :SHOHIN-CD, :SHOHIN-MEI, :TANKA
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-COUNT-RR
               WHEN 100
                   CONTINUE
               WHEN OTHER
                   PERFORM 9000-SQL-ERROR
           END-EVALUATE.

       8000-END.
           DISPLAY 'CSQ207 RS 件数 = ' WS-COUNT-RS
           DISPLAY 'CSQ207 RR 件数 = ' WS-COUNT-RR
           MOVE ZERO TO RETURN-CODE.

       9000-SQL-ERROR.
           DISPLAY 'CSQ207 SQL エラー SQLCODE = ' SQLCODE
           EXEC SQL CLOSE CSR-RSV END-EXEC
           EXEC SQL CLOSE CSR-RR END-EXEC
           MOVE 12 TO RETURN-CODE
           GOBACK.

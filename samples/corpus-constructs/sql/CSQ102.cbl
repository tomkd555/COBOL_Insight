      *----------------------------------------------------------------
      * CSQ102 -- constructs exercised:
      *   DECLARE CURSOR in PROCEDURE DIVISION; one SQL statement
      *   continued over many lines whose clauses start at different
      *   columns; a COBOL comment line (* in column 7) between two
      *   clauses of that statement; SQL -- end-of-line comments.
      *----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ102.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-JUCHU-NO                PIC X(10).
       01  WS-KOKYAKU-NO              PIC X(08).
       01  WS-JUCHU-YMD               PIC X(08).
       01  WS-GOKEI-GAKU              PIC S9(09) COMP-3.
       01  WS-JUCHU-KBN               PIC X(01).
       01  WS-FROM-YMD                PIC X(08).
           EXEC SQL END DECLARE SECTION END-EXEC.

       01  WS-COUNTERS.
           05  WS-READ-CNT            PIC 9(05) VALUE ZERO.

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-INIT
           PERFORM 2000-JUCHU-LOOP
           STOP RUN.

       1000-INIT.
           MOVE '1'        TO WS-JUCHU-KBN
           MOVE '20260101' TO WS-FROM-YMD
      *    the DECLARE CURSOR sits in the PROCEDURE DIVISION, right
      *    before the paragraph that opens it
           EXEC SQL
               DECLARE CSR-JUCHU CURSOR FOR
               SELECT JUCHU_NO, KOKYAKU_NO, JUCHU_YMD,
                        GOKEI_GAKU               -- 受注ヘッダー
      *    絞り込み条件は受注区分と対象年月日です
                 FROM CSQDB.JUCHU
                      WHERE JUCHU_KBN = :WS-JUCHU-KBN   -- 区分
                        AND JUCHU_YMD >= :WS-FROM-YMD
               ORDER BY JUCHU_NO
                    FOR FETCH ONLY
           END-EXEC.

       2000-JUCHU-LOOP.
           EXEC SQL OPEN CSR-JUCHU END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ102 OPEN異常 SQLCODE=' SQLCODE
           END-IF
           PERFORM 2100-FETCH-LOOP UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-JUCHU END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ102 CLOSE異常 SQLCODE=' SQLCODE
           END-IF.

       2100-FETCH-LOOP.
           EXEC SQL
               FETCH CSR-JUCHU
                INTO :WS-JUCHU-NO, :WS-KOKYAKU-NO, :WS-JUCHU-YMD,
                     :WS-GOKEI-GAKU
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-READ-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   DISPLAY 'CSQ102 FETCH異常 SQLCODE=' SQLCODE
           END-EVALUATE.

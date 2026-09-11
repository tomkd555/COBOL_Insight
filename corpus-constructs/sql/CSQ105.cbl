      *----------------------------------------------------------------
      * CSQ105 -- constructs exercised:
      *   a VARCHAR host variable, a level-49 LEN/TEXT pair under one
      *   01 group, used on both SELECT INTO and INSERT VALUES.
      *----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ105.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-SETSUMEI-NO             PIC X(06).
       01  WS-SETSUMEI-BUN.
           49  WS-SETSUMEI-BUN-LEN    PIC S9(4) COMP.
           49  WS-SETSUMEI-BUN-TXT    PIC X(100).
       01  WS-KOSHIN-YMD              PIC X(08).
           EXEC SQL END DECLARE SECTION END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           MOVE '000001'    TO WS-SETSUMEI-NO
           MOVE '20260101'  TO WS-KOSHIN-YMD
           PERFORM 1000-INSERT-VARCHAR
           PERFORM 2000-SELECT-VARCHAR
           EXEC SQL COMMIT END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ105 COMMIT異常 SQLCODE=' SQLCODE
           END-IF
           STOP RUN.

       1000-INSERT-VARCHAR.
           MOVE '本製品は月次バッチで自動的に更新されます。'
                TO WS-SETSUMEI-BUN-TXT
           MOVE 42 TO WS-SETSUMEI-BUN-LEN
           EXEC SQL
               INSERT INTO CSQDB.SETSUMEI
                      (SETSUMEI_NO, SETSUMEI_BUN, KOSHIN_YMD)
               VALUES (:WS-SETSUMEI-NO, :WS-SETSUMEI-BUN,
                       :WS-KOSHIN-YMD)
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ105 INSERT異常 SQLCODE=' SQLCODE
           END-IF.

       2000-SELECT-VARCHAR.
           EXEC SQL
               SELECT SETSUMEI_BUN
                 INTO :WS-SETSUMEI-BUN
                 FROM CSQDB.SETSUMEI
                WHERE SETSUMEI_NO = :WS-SETSUMEI-NO
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ105 SELECT異常 SQLCODE=' SQLCODE
           END-IF.

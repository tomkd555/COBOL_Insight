      *----------------------------------------------------------------
      * CSQ107 -- constructs exercised:
      *   typed literals X'0A' (hexadecimal), G'äøéö' (graphic) and
      *   N'äøéö' (national/Unicode) inside one INSERT statement.
      *   This master is rendered in Shift_JIS and in IBM930 to
      *   check the DBCS bytes inside the literals survive both
      *   encodings.
      *----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ107.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-HANBAI-NO               PIC X(06).
       01  WS-HANBAI-MEI              PIC X(30).
           EXEC SQL END DECLARE SECTION END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           MOVE '000001'      TO WS-HANBAI-NO
           MOVE 'êVãKéÊàµè§ïi' TO WS-HANBAI-MEI
           EXEC SQL
               INSERT INTO CSQDB.HANBAI
                      (HANBAI_NO, HANBAI_MEI, SEIGYO_BYTE,
                       KANJI_BIKO, UNICODE_BIKO)
               VALUES (:WS-HANBAI-NO, :WS-HANBAI-MEI, X'0A',
                       G'äøéö', N'äøéö')
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ107 INSERTàŸèÌ SQLCODE=' SQLCODE
           ELSE
               EXEC SQL COMMIT END-EXEC
               IF SQLCODE NOT = 0
                   DISPLAY 'CSQ107 COMMITàŸèÌ SQLCODE=' SQLCODE
               END-IF
           END-IF
           STOP RUN.

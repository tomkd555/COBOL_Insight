      *----------------------------------------------------------------
      * CSQ103 -- constructs exercised:
      *   a hyphenated host name next to a subtraction expression
      *   (:WS-A-B, one identifier, versus :WS-A - :WS-B, two host
      *   variables minus each other); a string literal that holds
      *   ':' and '--' and must not be read as a host variable or
      *   an SQL comment.
      *----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ103.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-SEISAN-NO               PIC X(06).
      *    WS-A and WS-B are two host variables; WS-A-B is a third,
      *    unrelated host variable whose name happens to contain the
      *    same two letters joined by hyphens
       01  WS-A                       PIC S9(09) COMP-3.
       01  WS-B                       PIC S9(09) COMP-3.
       01  WS-A-B                     PIC S9(09) COMP-3.
           EXEC SQL END DECLARE SECTION END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           MOVE '000001'  TO WS-SEISAN-NO
           MOVE 500       TO WS-A
           MOVE 120       TO WS-B
           PERFORM 1000-FETCH-A-B
           PERFORM 2000-SUBTRACT-A-B
           PERFORM 3000-INSERT-WITH-LITERAL
           EXEC SQL COMMIT END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ103 COMMIT異常 SQLCODE=' SQLCODE
           END-IF
           STOP RUN.

       1000-FETCH-A-B.
      *    :WS-A-B is one hyphenated host-variable name here, not a
      *    subtraction of two names
           EXEC SQL
               SELECT ZAN_GAKU
                 INTO :WS-A-B
                 FROM CSQDB.SEISAN
                WHERE SEISAN_NO = :WS-SEISAN-NO
           END-EXEC
           IF SQLCODE NOT = 0 AND SQLCODE NOT = 100
               DISPLAY 'CSQ103 SELECT異常 SQLCODE=' SQLCODE
           END-IF.

       2000-SUBTRACT-A-B.
      *    ":WS-A - :WS-B" is a subtraction of two separate host
      *    variables, spaced apart from the hyphen on both sides
           EXEC SQL
               UPDATE CSQDB.SEISAN
                  SET ZAN_GAKU = ZAN_GAKU - (:WS-A - :WS-B)
                WHERE SEISAN_NO = :WS-SEISAN-NO
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ103 UPDATE異常 SQLCODE=' SQLCODE
           END-IF.

       3000-INSERT-WITH-LITERAL.
      *    the literal below carries a colon and a double hyphen;
      *    neither marks a host variable or an SQL comment here
           EXEC SQL
               INSERT INTO CSQDB.SEISAN
                      (SEISAN_NO, ZAN_GAKU, BIKO)
               VALUES (:WS-SEISAN-NO, :WS-A-B,
                       '受付時間 09:00-10:00 --要確認--')
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ103 INSERT異常 SQLCODE=' SQLCODE
           END-IF.

       IDENTIFICATION DIVISION.
       PROGRAM-ID.  GOTOCOND.
       ENVIRONMENT DIVISION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-KUBUN     PIC X(01) VALUE '1'.
       01  WS-RESULT    PIC X(01) VALUE ' '.
       01  WS-COUNT     PIC 9(03) VALUE 0.
       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 6000-DECIDE THRU 6000-EXIT
           STOP RUN.
       6000-DECIDE.
           IF WS-KUBUN = '9'
               GO TO 6000-EXIT
           END-IF
           MOVE 'A' TO WS-RESULT
           ADD 1 TO WS-COUNT.
       6000-EXIT.
           EXIT.

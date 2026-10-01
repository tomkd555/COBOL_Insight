      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ305                                     *
      *  Constructs : CALL of a stored procedure with IN/OUT      *
      *               parameters; ASSOCIATE RESULT SET LOCATORS   *
      *               WITH PROCEDURE; ALLOCATE CURSOR FOR RESULT  *
      *               SET; DESCRIBE CURSOR ... INTO :SQLDA; FETCH *
      *               and CLOSE of the allocated cursor; GET      *
      *               DIAGNOSTICS ... = ROW_COUNT; GET             *
      *               DIAGNOSTICS EXCEPTION 1 ... = MESSAGE_TEXT. *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ305.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL INCLUDE SQLDA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-IN-CD                       PIC X(8).
       01  WS-OUT-CNT                     PIC S9(9) COMP.
       01  WS-OUT-RC                      PIC S9(9) COMP.
       01  WS-ROWS                        PIC S9(9) COMP VALUE ZERO.
       01  WS-DIAG-ROWS                   PIC S9(9) COMP.
       01  WS-MSG                         PIC X(70).
       01  WS-R-CD                        PIC X(8).
       01  WS-R-TANKA                     PIC S9(7)V9(2) COMP-3.
       01  WS-LOC1 USAGE SQL TYPE IS RESULT-SET-LOCATOR VARYING.
           EXEC SQL END DECLARE SECTION END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-CALL-PROC
           PERFORM 2000-FETCH-RESULT-SET
           PERFORM 3000-DIAGNOSTICS
           DISPLAY 'CSQ305 END'
           STOP RUN.

       1000-CALL-PROC.
           MOVE 'SH000001' TO WS-IN-CD
           EXEC SQL
               CALL CSQDB.SHUKEI1 (:WS-IN-CD, :WS-OUT-CNT,
                                    :WS-OUT-RC)
           END-EXEC
      *    +466 means the procedure returned open result sets;
      *    that is the normal path into 2000-FETCH-RESULT-SET.
           IF SQLCODE NOT = 0 AND SQLCODE NOT = +466
               PERFORM 9900-SQL-ERROR
           END-IF.

       2000-FETCH-RESULT-SET.
           EXEC SQL
               ASSOCIATE RESULT SET LOCATORS (:WS-LOC1)
                   WITH PROCEDURE CSQDB.SHUKEI1
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL
               ALLOCATE C1 CURSOR FOR RESULT SET :WS-LOC1
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9900-SQL-ERROR
           END-IF
           MOVE 10 TO SQLN
           EXEC SQL
               DESCRIBE CURSOR C1 INTO :SQLDA
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 2100-FETCH-LOOP UNTIL SQLCODE NOT = 0
      *    ROW_COUNT here describes the FETCH that just ended the
      *    loop (0 rows on SQLCODE +100), not the whole result set;
      *    WS-ROWS above already holds the cumulative fetch count.
           EXEC SQL
               GET DIAGNOSTICS :WS-DIAG-ROWS = ROW_COUNT
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL
               CLOSE C1
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9900-SQL-ERROR
           END-IF.

       2100-FETCH-LOOP.
           EXEC SQL
               FETCH C1 INTO :WS-R-CD, :WS-R-TANKA
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-ROWS
               WHEN 100
                   CONTINUE
               WHEN OTHER
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       3000-DIAGNOSTICS.
           DISPLAY 'CSQ305 結果件数 = ' WS-ROWS
           DISPLAY 'CSQ305 直前FETCHの取得行数 = ' WS-DIAG-ROWS.

       9900-SQL-ERROR.
           EXEC SQL
               GET DIAGNOSTICS EXCEPTION 1 :WS-MSG = MESSAGE_TEXT
           END-EXEC
           DISPLAY 'CSQ305 SQLエラー SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-MSG
           EXEC SQL ROLLBACK END-EXEC
           MOVE 12 TO RETURN-CODE
           GOBACK.

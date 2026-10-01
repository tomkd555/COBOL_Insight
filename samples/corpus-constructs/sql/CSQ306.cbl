      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ306                                     *
      *  Constructs : Dynamic SQL text built from literals only:  *
      *               EXECUTE IMMEDIATE; PREPARE ... FROM then    *
      *               EXECUTE ... USING; PREPARE ... INTO :SQLDA  *
      *               FROM then DESCRIBE INPUT ... INTO :SQLDA; a *
      *               dynamic cursor OPEN ... USING, DESCRIBE     *
      *               statement-name ... INTO :SQLDA, FETCH ...   *
      *               USING DESCRIPTOR :SQLDA.                    *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ306.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL INCLUDE SQLDA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-STMT-TEXT                   PIC X(80).
       01  WS-SEL-TEXT                    PIC X(90).
       01  WS-HINMOKU-CD                   PIC X(8).
       01  WS-ZAIKO-LIMIT                 PIC S9(9) COMP.
       01  WS-R-CD                        PIC X(8).
       01  WS-R-TANKA                     PIC S9(7)V9(2) COMP-3.
       01  WS-R-ZAIKO                     PIC S9(9) COMP.
       01  WS-ROWS                        PIC S9(9) COMP VALUE ZERO.
           EXEC SQL END DECLARE SECTION END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-EXECUTE-IMMEDIATE
           PERFORM 2000-PREPARE-EXECUTE
           PERFORM 3000-PREPARE-DESCRIBE-CURSOR
           DISPLAY 'CSQ306 END'
           STOP RUN.

       1000-EXECUTE-IMMEDIATE.
           MOVE SPACES TO WS-STMT-TEXT
           STRING 'UPDATE CSQDB.HINMOKU SET KOSHIN_YMD = CURRENT '
                  'DATE WHERE HINMOKU_CD = ''SH000002'''
               DELIMITED BY SIZE
               INTO WS-STMT-TEXT
               ON OVERFLOW
                   DISPLAY 'CSQ306 動的SQL生成エラー'
                   MOVE 12 TO RETURN-CODE
                   GOBACK
           END-STRING
           EXEC SQL
               EXECUTE IMMEDIATE :WS-STMT-TEXT
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9900-SQL-ERROR
           END-IF.

       2000-PREPARE-EXECUTE.
           MOVE SPACES TO WS-STMT-TEXT
           STRING 'UPDATE CSQDB.HINMOKU SET KOSHIN_YMD = CURRENT '
                  'DATE WHERE HINMOKU_CD = ?'
               DELIMITED BY SIZE
               INTO WS-STMT-TEXT
               ON OVERFLOW
                   DISPLAY 'CSQ306 動的SQL生成エラー'
                   MOVE 12 TO RETURN-CODE
                   GOBACK
           END-STRING
           EXEC SQL
               PREPARE S1 FROM :WS-STMT-TEXT
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9900-SQL-ERROR
           END-IF
           MOVE 'SH000003' TO WS-HINMOKU-CD
           EXEC SQL
               EXECUTE S1 USING :WS-HINMOKU-CD
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9900-SQL-ERROR
           END-IF.

       3000-PREPARE-DESCRIBE-CURSOR.
           MOVE SPACES TO WS-SEL-TEXT
           STRING 'SELECT HINMOKU_CD, TANKA, ZAIKO_SU '
                  'FROM CSQDB.HINMOKU WHERE ZAIKO_SU < ? '
                  'FOR FETCH ONLY'
               DELIMITED BY SIZE
               INTO WS-SEL-TEXT
               ON OVERFLOW
                   DISPLAY 'CSQ306 動的SQL生成エラー'
                   MOVE 12 TO RETURN-CODE
                   GOBACK
           END-STRING
           MOVE 10 TO SQLN
           EXEC SQL
               PREPARE S2 INTO :SQLDA FROM :WS-SEL-TEXT
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL
               DESCRIBE INPUT S2 INTO :SQLDA
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL
               DECLARE C2 CURSOR FOR S2
           END-EXEC
           MOVE 10 TO WS-ZAIKO-LIMIT
           EXEC SQL
               OPEN C2 USING :WS-ZAIKO-LIMIT
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9900-SQL-ERROR
           END-IF
      *----- Reload :SQLDA with the SELECT list description; the
      *     DESCRIBE INPUT above overwrote it with the parameter
      *     marker description, and SQLDATA(n) must point at the
      *     output columns, not the input parameter. -----
           MOVE 10 TO SQLN
           EXEC SQL
               DESCRIBE S2 INTO :SQLDA
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9900-SQL-ERROR
           END-IF
           SET SQLDATA(1) TO ADDRESS OF WS-R-CD
           SET SQLDATA(2) TO ADDRESS OF WS-R-TANKA
           SET SQLDATA(3) TO ADDRESS OF WS-R-ZAIKO
           PERFORM 3100-FETCH-C2 UNTIL SQLCODE NOT = 0
           EXEC SQL
               CLOSE C2
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9900-SQL-ERROR
           END-IF.

       3100-FETCH-C2.
           EXEC SQL
               FETCH C2 USING DESCRIPTOR :SQLDA
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-ROWS
               WHEN 100
                   CONTINUE
               WHEN OTHER
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       9900-SQL-ERROR.
           DISPLAY 'CSQ306 SQLエラー SQLCODE = ' SQLCODE
           EXEC SQL ROLLBACK END-EXEC
           MOVE 12 TO RETURN-CODE
           GOBACK.

      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ304                                     *
      *  Constructs : SET :host = CURRENT DATE / CURRENT          *
      *               TIMESTAMP; SET CURRENT SQLID, CURRENT       *
      *               DEGREE, CURRENT PACKAGESET, CURRENT PATH,   *
      *               CURRENT SCHEMA; VALUES NEXT VALUE FOR a     *
      *               sequence INTO a host variable; VALUES       *
      *               (CURRENT TIMESTAMP, CURRENT USER) INTO two  *
      *               host variables.                             *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ304.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-TODAY                       PIC X(10).
       01  WS-TS                          PIC X(26).
       01  WS-SQLID                       PIC X(8) VALUE 'PRODUSR1'.
       01  WS-NEW-ID                      PIC S9(9) COMP.
       01  WS-CUR-TS                      PIC X(26).
       01  WS-CUR-USER.
           49  WS-CUR-USER-LEN            PIC S9(4) COMP.
           49  WS-CUR-USER-TXT            PIC X(128).
           EXEC SQL END DECLARE SECTION END-EXEC.

       01  WS-ERR-MSG                     PIC X(60).

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-SET-DATE-TS
           PERFORM 2000-SET-SPECIAL-REGISTERS
           PERFORM 3000-NEXT-SEQ
           PERFORM 4000-VALUES-MULTI
           DISPLAY 'CSQ304 END'
           STOP RUN.

       1000-SET-DATE-TS.
           EXEC SQL
               SET :WS-TODAY = CURRENT DATE
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '日付取得エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL
               SET :WS-TS = CURRENT TIMESTAMP
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '時刻印取得エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       2000-SET-SPECIAL-REGISTERS.
           EXEC SQL
               SET CURRENT SQLID = :WS-SQLID
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'SQLID設定エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL
               SET CURRENT DEGREE = '1'
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'DEGREE設定エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL
               SET CURRENT PACKAGESET = 'COLL1'
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'PACKAGESET設定エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL
               SET CURRENT PATH = SYSIBM
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'PATH設定エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL
               SET CURRENT SCHEMA = 'PROD'
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'SCHEMA設定エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       3000-NEXT-SEQ.
           EXEC SQL
               VALUES NEXT VALUE FOR CSQDB.SEQ1
                   INTO :WS-NEW-ID
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '採番エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       4000-VALUES-MULTI.
           EXEC SQL
               VALUES (CURRENT TIMESTAMP, CURRENT USER)
                   INTO :WS-CUR-TS, :WS-CUR-USER
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '複数値取得エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       9900-SQL-ERROR.
           DISPLAY 'CSQ304 SQLエラー SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-ERR-MSG
           EXEC SQL ROLLBACK END-EXEC
           MOVE 12 TO RETURN-CODE
           GOBACK.

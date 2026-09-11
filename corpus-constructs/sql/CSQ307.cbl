      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ307                                     *
      *  Constructs : CONNECT TO a remote location; SET           *
      *               CONNECTION; RELEASE CURRENT; EXPLAIN PLAN   *
      *               SET QUERYNO = n FOR a SELECT; a GRANT        *
      *               statement executed through EXECUTE          *
      *               IMMEDIATE.                                   *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ307.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-HINMOKU-CD                   PIC X(8).
       01  WS-GRANT-STMT                  PIC X(60).
           EXEC SQL END DECLARE SECTION END-EXEC.

       01  WS-ERR-MSG                     PIC X(60).

       PROCEDURE DIVISION.
      *    2000 and 3000 run against the local connection that is
      *    already in effect on entry; 1000 connects to the remote
      *    site and releases it last, so neither local paragraph
      *    ever runs unconnected.
       0000-MAIN.
           PERFORM 2000-EXPLAIN-PLAN
           PERFORM 3000-GRANT-VIA-EXEC-IMM
           PERFORM 1000-CONNECT-REMOTE
           DISPLAY 'CSQ307 END'
           STOP RUN.

       1000-CONNECT-REMOTE.
           EXEC SQL
               CONNECT TO PRODLOC1
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '接続エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL
               SET CONNECTION PRODLOC1
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '接続切替エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL
               RELEASE CURRENT
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '接続解放エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL COMMIT END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'コミットエラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       2000-EXPLAIN-PLAN.
           MOVE 'SH000001' TO WS-HINMOKU-CD
           EXEC SQL
               EXPLAIN PLAN SET QUERYNO = 100 FOR
                   SELECT HINMOKU_CD, TANKA, ZAIKO_SU
                     FROM CSQDB.HINMOKU
                    WHERE HINMOKU_CD = :WS-HINMOKU-CD
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '実行計画取得エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       3000-GRANT-VIA-EXEC-IMM.
           MOVE 'GRANT SELECT ON CSQDB.HINMOKU TO PUBLIC'
               TO WS-GRANT-STMT
           EXEC SQL
               EXECUTE IMMEDIATE :WS-GRANT-STMT
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '権限付与エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       9900-SQL-ERROR.
           DISPLAY 'CSQ307 SQLエラー SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-ERR-MSG
           EXEC SQL ROLLBACK END-EXEC
           MOVE 12 TO RETURN-CODE
           GOBACK.

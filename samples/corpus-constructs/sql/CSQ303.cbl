      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ303                                     *
      *  Constructs : MERGE INTO ... USING (VALUES ...) AS ... ;  *
      *               TRUNCATE TABLE ... IMMEDIATE; LOCK TABLE    *
      *               ... IN EXCLUSIVE MODE; COMMIT; ROLLBACK;    *
      *               SAVEPOINT ... ON ROLLBACK RETAIN CURSORS;   *
      *               ROLLBACK TO SAVEPOINT; RELEASE SAVEPOINT.   *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ303.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL INCLUDE CSD302 END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-MERGE-KEY                   PIC X(8).
       01  WS-MERGE-VAL                   PIC S9(7)V9(2) COMP-3.
           EXEC SQL END DECLARE SECTION END-EXEC.

       01  WS-ERR-MSG                     PIC X(60).

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-TRUNCATE-WORK
           PERFORM 2000-LOCK-WORK
           PERFORM 3000-MERGE-WORK
           PERFORM 4000-COMMIT-WORK
           PERFORM 5000-SAVEPOINT-DEMO
           DISPLAY 'CSQ303 END'
           STOP RUN.

       1000-TRUNCATE-WORK.
           EXEC SQL
               TRUNCATE TABLE CSQDB.WORK_TBL IMMEDIATE
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '作業表初期化エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       2000-LOCK-WORK.
           EXEC SQL
               LOCK TABLE CSQDB.WORK_TBL IN EXCLUSIVE MODE
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '作業表排他エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       3000-MERGE-WORK.
           MOVE 'WK000001' TO WS-MERGE-KEY
           MOVE 1000.00    TO WS-MERGE-VAL
           EXEC SQL
               MERGE INTO CSQDB.WORK_TBL AS T
                   USING (VALUES (:WS-MERGE-KEY, :WS-MERGE-VAL))
                       AS S (K, V)
                   ON T.K = S.K
                   WHEN MATCHED THEN
                       UPDATE SET V = S.V
                   WHEN NOT MATCHED THEN
                       INSERT (K, V) VALUES (S.K, S.V)
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '作業表反映エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       4000-COMMIT-WORK.
           EXEC SQL COMMIT END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'コミットエラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       5000-SAVEPOINT-DEMO.
           EXEC SQL
               SAVEPOINT SP1 ON ROLLBACK RETAIN CURSORS
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'セーブポイント設定エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           MOVE 'WK000002' TO WS-MERGE-KEY
           MOVE 2000.00    TO WS-MERGE-VAL
           EXEC SQL
               UPDATE CSQDB.WORK_TBL
                  SET V = :WS-MERGE-VAL
                WHERE K = :WS-MERGE-KEY
           END-EXEC
           IF SQLCODE NOT = 0
               EXEC SQL
                   ROLLBACK TO SAVEPOINT SP1
               END-EXEC
               IF SQLCODE NOT = 0
                   MOVE '復帰エラー' TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
               END-IF
           ELSE
               EXEC SQL
                   RELEASE SAVEPOINT SP1
               END-EXEC
               IF SQLCODE NOT = 0
                   MOVE '解放エラー' TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
               END-IF
           END-IF.

       9900-SQL-ERROR.
           DISPLAY 'CSQ303 SQLエラー SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-ERR-MSG
           EXEC SQL ROLLBACK END-EXEC
           MOVE 12 TO RETURN-CODE
           GOBACK.

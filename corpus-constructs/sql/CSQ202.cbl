      *----------------------------------------------------------------*
      * PROGRAM-ID : CSQ202
      * Constructs : EXEC SQL INCLUDE CSI201 END-EXEC, an INCLUDE
      *   member that itself carries executable SQL (a DECLARE CURSOR
      *   and a SELECT INTO); a cursor declared FOR UPDATE OF two
      *   columns; a positioned UPDATE ... WHERE CURRENT OF against
      *   that cursor.
      *----------------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    CSQ202.
       AUTHOR.        CSQ-CONSTRUCTS-DEV.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       SOURCE-COMPUTER. IBM-370.
       OBJECT-COMPUTER. IBM-370.

       DATA DIVISION.
       WORKING-STORAGE SECTION.

           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL INCLUDE CSD201 END-EXEC.

       01  WS-PARM-AREA.
           05  WS-PARM-SHOHIN-CD      PIC X(08).
           05  FILLER                 PIC X(72).

       01  WS-ERR-MSG                 PIC X(60) VALUE SPACE.
       01  WS-UPD-CNT                 PIC 9(05) VALUE ZERO.

       PROCEDURE DIVISION.

       0000-MAIN.
           PERFORM 1000-INIT
           PERFORM 2000-LOOKUP-AND-OPEN
           PERFORM 3000-UPD-LOOP UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-UPD END-EXEC
           PERFORM 8000-END
           STOP RUN.

       1000-INIT.
           ACCEPT WS-PARM-AREA
           MOVE WS-PARM-SHOHIN-CD TO SHOHIN-CD.

      *----- CSI201 が持ち込む DECLARE CURSOR と SELECT INTO -----
       2000-LOOKUP-AND-OPEN.
           EXEC SQL INCLUDE CSI201 END-EXEC
           IF SQLCODE NOT = 0
               MOVE '商品名の取得に失敗しました' TO WS-ERR-MSG
               PERFORM 9000-SQL-ERROR
           END-IF
           DISPLAY 'CSQ202 商品名 = ' SHOHIN-MEI
           EXEC SQL OPEN CSR-UPD END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'カーソルの開始に失敗しました' TO WS-ERR-MSG
               PERFORM 9000-SQL-ERROR
           END-IF.

       3000-UPD-LOOP.
           EXEC SQL
               FETCH CSR-UPD
                INTO :SHOHIN-CD, :TANKA, :ZAIKO-SU
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   PERFORM 3100-UPD-TANKA
               WHEN 100
                   CONTINUE
               WHEN OTHER
                   MOVE 'フェッチに失敗しました' TO WS-ERR-MSG
                   PERFORM 9000-SQL-ERROR
           END-EVALUATE.

       3100-UPD-TANKA.
           COMPUTE TANKA = TANKA * 1.05
           EXEC SQL
               UPDATE CSQDB.SHOHIN
                  SET TANKA = :TANKA,
                      ZAIKO_SU = ZAIKO_SU - 1
                WHERE CURRENT OF CSR-UPD
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '単価更新に失敗しました' TO WS-ERR-MSG
               PERFORM 9000-SQL-ERROR
           END-IF
           ADD 1 TO WS-UPD-CNT.

       8000-END.
           EXEC SQL COMMIT END-EXEC
           DISPLAY 'CSQ202 更新件数 = ' WS-UPD-CNT
           MOVE ZERO TO RETURN-CODE.

       9000-SQL-ERROR.
           DISPLAY 'CSQ202 SQL エラー SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-ERR-MSG
           EXEC SQL CLOSE CSR-UPD END-EXEC
           EXEC SQL ROLLBACK END-EXEC
           MOVE 12 TO RETURN-CODE
           GOBACK.

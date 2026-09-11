      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ301                                     *
      *  Constructs : SELECT INTO with a single-row primary-key   *
      *               predicate; INSERT ... VALUES; INSERT ...    *
      *               SELECT from a fullselect; multi-row INSERT  *
      *               with host-variable arrays, FOR 3 ROWS.      *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ301.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL INCLUDE CSD301 END-EXEC.

           EXEC SQL
               DECLARE CSQDB.HINMOKU_LOG TABLE
               ( HINMOKU_CD              CHAR(8) NOT NULL,
                 HINMOKU_MEI             VARCHAR(40) NOT NULL,
                 TANKA                  DECIMAL(9, 2) NOT NULL,
                 KOSHIN_YMD             CHAR(8) )
           END-EXEC.

           EXEC SQL
               DECLARE CSQDB.HINMOKU_BULK TABLE
               ( HINMOKU_CD              CHAR(8) NOT NULL,
                 TANKA                  DECIMAL(9, 2) NOT NULL,
                 ZAIKO_SU               INTEGER NOT NULL )
           END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-HINMOKU-CD                   PIC X(8).
       01  WS-BIKO-IND                    PIC S9(4) COMP.
       01  WS-KOSHIN-YMD-IND              PIC S9(4) COMP.
       01  WS-BULK-TBL.
           05  WS-BULK-CD             PIC X(8) OCCURS 3 TIMES.
           05  WS-BULK-TANKA          PIC S9(7)V9(2) COMP-3
                                           OCCURS 3 TIMES.
           05  WS-BULK-ZAIKO          PIC S9(9) COMP
                                           OCCURS 3 TIMES.
           EXEC SQL END DECLARE SECTION END-EXEC.

       01  WS-ERR-MSG                     PIC X(60).

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-SELECT-BY-PK
           PERFORM 2000-INSERT-VALUES
           PERFORM 3000-INSERT-FULLSELECT
           PERFORM 4000-INSERT-MULTIROW
           DISPLAY 'CSQ301 END'
           STOP RUN.

       1000-SELECT-BY-PK.
           MOVE 'SH000001' TO WS-HINMOKU-CD
           EXEC SQL
               SELECT HINMOKU_CD, HINMOKU_MEI, TANKA, ZAIKO_SU, BIKO,
                      KOSHIN_YMD
                 INTO :HINMOKU-CD, :HINMOKU-MEI, :TANKA, :ZAIKO-SU,
                      :BIKO :WS-BIKO-IND,
                      :KOSHIN-YMD :WS-KOSHIN-YMD-IND
                 FROM CSQDB.HINMOKU
                WHERE HINMOKU_CD = :WS-HINMOKU-CD
                FETCH FIRST 1 ROW ONLY
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '品目検索エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       2000-INSERT-VALUES.
           MOVE 'SH000002'        TO HINMOKU-CD
           MOVE 16                TO HINMOKU-MEI-LEN
           MOVE 'SAMPLE HINMOKU B' TO HINMOKU-MEI-TEXT
           MOVE 1500.00           TO TANKA
           MOVE 100                TO ZAIKO-SU
           MOVE 10                 TO BIKO-LEN
           MOVE 'NO REMARKS'       TO BIKO-TEXT
           MOVE ZERO                TO WS-BIKO-IND
           MOVE -1                  TO WS-KOSHIN-YMD-IND
           EXEC SQL
               INSERT INTO CSQDB.HINMOKU
                      (HINMOKU_CD, HINMOKU_MEI, TANKA, ZAIKO_SU, BIKO,
                       KOSHIN_YMD)
               VALUES (:HINMOKU-CD, :HINMOKU-MEI, :TANKA, :ZAIKO-SU,
                       :BIKO :WS-BIKO-IND,
                       :KOSHIN-YMD :WS-KOSHIN-YMD-IND)
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '品目登録エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       3000-INSERT-FULLSELECT.
           EXEC SQL
               INSERT INTO CSQDB.HINMOKU_LOG
                      (HINMOKU_CD, HINMOKU_MEI, TANKA, KOSHIN_YMD)
               SELECT HINMOKU_CD, HINMOKU_MEI, TANKA, KOSHIN_YMD
                 FROM CSQDB.HINMOKU
                WHERE HINMOKU_CD = :WS-HINMOKU-CD
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '履歴複写エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       4000-INSERT-MULTIROW.
           MOVE 'SH000101' TO WS-BULK-CD(1)
           MOVE 'SH000102' TO WS-BULK-CD(2)
           MOVE 'SH000103' TO WS-BULK-CD(3)
           MOVE 500.00     TO WS-BULK-TANKA(1)
           MOVE 600.00     TO WS-BULK-TANKA(2)
           MOVE 700.00     TO WS-BULK-TANKA(3)
           MOVE 10         TO WS-BULK-ZAIKO(1)
           MOVE 20         TO WS-BULK-ZAIKO(2)
           MOVE 30         TO WS-BULK-ZAIKO(3)
           EXEC SQL
               INSERT INTO CSQDB.HINMOKU_BULK
                      (HINMOKU_CD, TANKA, ZAIKO_SU)
               VALUES (:WS-BULK-CD, :WS-BULK-TANKA, :WS-BULK-ZAIKO)
               FOR 3 ROWS
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '一括登録エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       9900-SQL-ERROR.
           DISPLAY 'CSQ301 SQLエラー SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-ERR-MSG
           EXEC SQL ROLLBACK END-EXEC
           MOVE 12 TO RETURN-CODE
           GOBACK.

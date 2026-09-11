      *----------------------------------------------------------------*
      * PROGRAM-ID : CSQ208
      * Constructs : EXEC SQL DECLARE host-variable VARIABLE CCSID
      *   930 (a DBCS/Japanese host variable); PREPARE into a
      *   literal statement name (S2) followed by EXECUTE ... USING;
      *   PREPARE into a literal statement name (S1) followed by
      *   DECLARE CURSOR FOR S1 and OPEN ... USING a host variable.
      *   DECLARE STATEMENT takes an SQL statement-name, not a host
      *   variable, so "DECLARE :WS-STMT STATEMENT" is not valid Db2
      *   for z/OS syntax; this program declares the literal
      *   statement name S2 instead.
      *----------------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    CSQ208.
       AUTHOR.        CSQ-CONSTRUCTS-DEV.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       SOURCE-COMPUTER. IBM-370.
       OBJECT-COMPUTER. IBM-370.

       DATA DIVISION.
       WORKING-STORAGE SECTION.

           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL INCLUDE CSD201 END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-KANJI-MEI                 PIC X(40).
       01  WS-SELECT-SQL                PIC X(120).
       01  WS-DYN-SQL                   PIC X(120).
           EXEC SQL END DECLARE SECTION END-EXEC.

           EXEC SQL DECLARE :WS-KANJI-MEI VARIABLE CCSID 930 END-EXEC.
           EXEC SQL DECLARE S2 STATEMENT END-EXEC.

       01  WS-PARM-AREA.
           05  WS-PARM-KANJI-MEI       PIC X(40).
           05  FILLER                  PIC X(36).

       01  WS-COUNT                    PIC 9(05) VALUE ZERO.

       PROCEDURE DIVISION.

       0000-MAIN.
           PERFORM 1000-INIT
           PERFORM 2000-DYN-UPDATE
           PERFORM 3000-DYN-CURSOR
           PERFORM 8000-END
           STOP RUN.

       1000-INIT.
           ACCEPT WS-PARM-AREA
           MOVE WS-PARM-KANJI-MEI TO WS-KANJI-MEI.

      *----- DECLARE S2 STATEMENT を使う動的 UPDATE -----
       2000-DYN-UPDATE.
           STRING 'UPDATE CSQDB.SHOHIN SET KOSHIN_YMD = '
                  'VARCHAR_FORMAT(CURRENT DATE, ''YYYYMMDD'') '
                  'WHERE SHOHIN_MEI = ?'
               DELIMITED BY SIZE INTO WS-DYN-SQL
               ON OVERFLOW
                   PERFORM 9000-SQL-ERROR
               NOT ON OVERFLOW
                   CONTINUE
           END-STRING
           EXEC SQL PREPARE S2 FROM :WS-DYN-SQL END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-SQL-ERROR
           END-IF
           EXEC SQL EXECUTE S2 USING :WS-KANJI-MEI END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-SQL-ERROR
           END-IF.

      *----- PREPARE S1 と DECLARE C2 CURSOR FOR S1 の動的カーソル -----
       3000-DYN-CURSOR.
           STRING 'SELECT SHOHIN_CD, SHOHIN_MEI, TANKA '
                  'FROM CSQDB.SHOHIN WHERE SHOHIN_MEI = ? '
                  'FOR FETCH ONLY'
               DELIMITED BY SIZE INTO WS-SELECT-SQL
               ON OVERFLOW
                   PERFORM 9000-SQL-ERROR
               NOT ON OVERFLOW
                   CONTINUE
           END-STRING
           EXEC SQL PREPARE S1 FROM :WS-SELECT-SQL END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-SQL-ERROR
           END-IF
           EXEC SQL DECLARE C2 CURSOR FOR S1 END-EXEC
           EXEC SQL OPEN C2 USING :WS-KANJI-MEI END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-SQL-ERROR
           END-IF
           PERFORM 3100-DYN-FETCH-LOOP UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE C2 END-EXEC.

       3100-DYN-FETCH-LOOP.
           EXEC SQL
               FETCH C2 INTO :SHOHIN-CD, :SHOHIN-MEI, :TANKA
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-COUNT
               WHEN 100
                   CONTINUE
               WHEN OTHER
                   PERFORM 9000-SQL-ERROR
           END-EVALUATE.

       8000-END.
           EXEC SQL COMMIT END-EXEC
           DISPLAY 'CSQ208 一致件数 = ' WS-COUNT
           MOVE ZERO TO RETURN-CODE.

       9000-SQL-ERROR.
           DISPLAY 'CSQ208 SQL エラー SQLCODE = ' SQLCODE
           EXEC SQL CLOSE C2 END-EXEC
           EXEC SQL ROLLBACK END-EXEC
           MOVE 12 TO RETURN-CODE
           GOBACK.

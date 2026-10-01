      *----------------------------------------------------------------
      * CSQ104 -- constructs exercised:
      *   a host structure INTO :WS-SHAIN-REC whose group is declared
      *   in WORKING-STORAGE; dot-form host-variable qualification
      *   (:GROUP.FIELD) of two fields inside it; a table element
      *   copied into an elementary host variable before the SQL
      *   statement, because Db2 for z/OS does not accept a
      *   subscripted host variable inside an SQL statement. The
      *   COBOL OF-qualification form (:FIELD OF GROUP) and a
      *   subscripted host variable inside SQL (:WS-TBL(1)) are both
      *   precompiler-invalid; CSQ110.cbl keeps one occurrence of each
      *   to exercise the analyser's tolerance of them.
      *----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ104.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-SHAIN-REC.
           05  SHAIN-NO               PIC X(06).
           05  SHAIN-MEI              PIC X(20).
           05  BUSHO-CD               PIC X(04).
           05  NYUSHA-YMD             PIC X(08).
       01  WS-KENSU-1                 PIC S9(05) COMP-3.
       01  WS-KENSU-2                 PIC S9(05) COMP-3.
       01  WS-KENSU-3                 PIC S9(05) COMP-3.
       01  WS-BUSHO-CD                PIC X(04).
           EXEC SQL END DECLARE SECTION END-EXEC.

       01  WS-BUSHO-TBL-AREA.
           05  WS-BUSHO-TBL           PIC X(04) OCCURS 5 TIMES.
       01  WS-IX                      PIC S9(04) COMP VALUE 2.

       PROCEDURE DIVISION.
       0000-MAIN.
           MOVE '100001' TO SHAIN-NO
           MOVE '経理'   TO WS-BUSHO-TBL(1)
           MOVE '営業'   TO WS-BUSHO-TBL(2)
           PERFORM 1000-STRUCT-SELECT
           PERFORM 2000-QUALIFIED-COUNT
           PERFORM 3000-SUBSCRIPTED-COUNT
           STOP RUN.

       1000-STRUCT-SELECT.
      *    the whole group WS-SHAIN-REC receives one row, field by
      *    field in declaration order; every column here is required
      *    so no indicator variable is needed
           EXEC SQL
               SELECT SHAIN_NO, SHAIN_MEI, BUSHO_CD, NYUSHA_YMD
                 INTO :WS-SHAIN-REC
                 FROM CSQDB.SHAIN
                WHERE SHAIN_NO = :SHAIN-NO
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ104 SELECT異常(1) SQLCODE=' SQLCODE
           END-IF.

       2000-QUALIFIED-COUNT.
      *    :WS-SHAIN-REC.BUSHO-CD and :WS-SHAIN-REC.SHAIN-NO are both
      *    the dot-notation form Db2 for z/OS accepts
           EXEC SQL
               SELECT COUNT(*)
                 INTO :WS-KENSU-1
                 FROM CSQDB.SHAIN
                WHERE BUSHO_CD = :WS-SHAIN-REC.BUSHO-CD
                  AND SHAIN_NO <> :WS-SHAIN-REC.SHAIN-NO
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ104 SELECT異常(2) SQLCODE=' SQLCODE
           END-IF.

       3000-SUBSCRIPTED-COUNT.
      *    Db2 for z/OS does not accept a subscript inside an SQL
      *    statement, so the table element is copied into an
      *    elementary host variable first, once with a literal
      *    subscript and once with a variable subscript
           MOVE WS-BUSHO-TBL(1) TO WS-BUSHO-CD
           EXEC SQL
               SELECT COUNT(*)
                 INTO :WS-KENSU-2
                 FROM CSQDB.SHAIN
                WHERE BUSHO_CD = :WS-BUSHO-CD
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ104 SELECT異常(3) SQLCODE=' SQLCODE
           END-IF
           MOVE WS-BUSHO-TBL(WS-IX) TO WS-BUSHO-CD
           EXEC SQL
               SELECT COUNT(*)
                 INTO :WS-KENSU-3
                 FROM CSQDB.SHAIN
                WHERE BUSHO_CD = :WS-BUSHO-CD
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ104 SELECT異常(4) SQLCODE=' SQLCODE
           END-IF.

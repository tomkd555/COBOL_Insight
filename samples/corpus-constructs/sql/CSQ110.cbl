      *----------------------------------------------------------------
      * CSQ110 -- tolerance-only constructs, NOT valid Db2 for z/OS
      *   precompiler input:
      *     :FIELD OF GROUP (COBOL OF-qualification of a host
      *       variable) -- Db2 for z/OS accepts only the dot form,
      *       :GROUP.FIELD (see CSQ104.cbl)
      *     :WS-TBL(1) (a subscripted host variable inside an SQL
      *       statement) -- Db2 for z/OS forbids subscripts inside
      *       SQL statements
      *   Both forms are kept here, one occurrence each, only to
      *   exercise how tolerant the analyser is of syntax a real Db2
      *   precompiler or coprocessor would reject; neither paragraph
      *   is a template to copy elsewhere in this corpus.
      *----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ110.

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
       01  WS-BUSHO-TBL-AREA.
           05  WS-BUSHO-TBL           PIC X(04) OCCURS 5 TIMES.
           EXEC SQL END DECLARE SECTION END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           MOVE '100001' TO SHAIN-NO
           MOVE '経理'   TO WS-BUSHO-TBL(1)
           PERFORM 1000-OF-QUALIFIED-COUNT
           PERFORM 2000-SUBSCRIPTED-COUNT
           STOP RUN.

       1000-OF-QUALIFIED-COUNT.
      *    :BUSHO-CD OF WS-SHAIN-REC -- precompiler-invalid, kept for
      *    tolerance testing only
           EXEC SQL
               SELECT COUNT(*)
                 INTO :WS-KENSU-1
                 FROM CSQDB.SHAIN
                WHERE BUSHO_CD = :BUSHO-CD OF WS-SHAIN-REC
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ110 SELECT異常(1) SQLCODE=' SQLCODE
           END-IF.

       2000-SUBSCRIPTED-COUNT.
      *    :WS-BUSHO-TBL(1) -- precompiler-invalid, kept for
      *    tolerance testing only
           EXEC SQL
               SELECT COUNT(*)
                 INTO :WS-KENSU-2
                 FROM CSQDB.SHAIN
                WHERE BUSHO_CD = :WS-BUSHO-TBL(1)
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ110 SELECT異常(2) SQLCODE=' SQLCODE
           END-IF.

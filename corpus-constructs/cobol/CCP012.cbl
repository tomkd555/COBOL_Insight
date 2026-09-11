      *----------------------------------------------------------*
      *  PROGRAM-ID : CCP012                                     *
      *  Constructs : Db2 shared stub, EXEC SQL INCLUDE SQLCA,   *
      *               SELECT INTO with SQLCODE check, DISPLAY,   *
      *               GOBACK. Shared batch stub for corpus-      *
      *               constructs JCL fixtures.                   *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CCP012.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  HOST-CCP-KEY                   PIC X(08).
       01  HOST-CCP-VAL                   PIC S9(09) COMP-3.
           EXEC SQL END DECLARE SECTION END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           MOVE 'STUB0001' TO HOST-CCP-KEY
           EXEC SQL
               SELECT CCP_VAL
                 INTO :HOST-CCP-VAL
                 FROM CCPDB.CCPTAB
                WHERE CCP_KEY = :HOST-CCP-KEY
                FETCH FIRST 1 ROW ONLY
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   CONTINUE
               WHEN +100
                   DISPLAY 'CCP012 対象なし SQLCODE=' SQLCODE
               WHEN OTHER
                   DISPLAY 'CCP012 SELECT異常 SQLCODE=' SQLCODE
                   MOVE 12 TO RETURN-CODE
           END-EVALUATE
           DISPLAY 'CCP012 END'
           GOBACK.

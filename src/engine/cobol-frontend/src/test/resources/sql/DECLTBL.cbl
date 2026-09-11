       IDENTIFICATION DIVISION.
       PROGRAM-ID. DECLTBL.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-ACCTNO          PIC X(8).
       01  WS-SURNAME         PIC X(20).
           EXEC SQL END DECLARE SECTION END-EXEC.
      *****************************************************
      * SQL DECLARATION FOR VIEW ACCOUNTS                 *
      *****************************************************
           EXEC SQL DECLARE Z#####T TABLE
                   (ACCTNO     CHAR(8)  NOT NULL,                       SEQ00140
                    SURNAME    CHAR(20) NOT NULL)
                    END-EXEC.
      *****************************************************
      * SQL CURSORS                                       *
      *****************************************************
           EXEC SQL DECLARE CUR1  CURSOR FOR
                    SELECT ACCTNO, SURNAME FROM Z#####T
                END-EXEC.
       PROCEDURE DIVISION.
       MAIN-PARA.
           EXEC SQL WHENEVER SQLERROR CONTINUE END-EXEC.
           EXEC SQL OPEN CUR1 END-EXEC.
           EXEC SQL
               FETCH CUR1 INTO :WS-ACCTNO, :WS-SURNAME
           END-EXEC.
           EXEC SQL CLOSE CUR1 END-EXEC.
           GOBACK.

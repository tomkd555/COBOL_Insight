      *----------------------------------------------------------*
      *  DCLGEN     : CSD303                                     *
      *  Constructs : DCLGEN of CSQDB.BUNSHO; the DECLARE TABLE   *
      *               lists the table's XML/BLOB/CLOB columns    *
      *               for documentation, but the COBOL 01 group  *
      *               omits them, since a LOB/XML column has no  *
      *               plain PICTURE host variable and is instead *
      *               declared at its point of use (locator or   *
      *               file-reference host variable).             *
      *----------------------------------------------------------*
      ******************************************************************
      * DCLGEN TABLE(CSQDB.BUNSHO)                                     *
      *        LIBRARY(CSQ.PROD.DCLGEN(CSD303))                        *
      *        ACTION(REPLACE)                                         *
      *        LANGUAGE(COBOL)                                         *
      *        APOST                                                   *
      *     ... IS THE DCLGEN COMMAND THAT MADE THE FOLLOWING STATEMENTS
      ******************************************************************
           EXEC SQL DECLARE CSQDB.BUNSHO TABLE
           ( BUNSHO_ID                    INTEGER NOT NULL,
             BUNSHO_MEI                   VARCHAR(60) NOT NULL,
             NAIYOU_XML                   XML,
             GAZOU                        BLOB(1M),
             TEISAI                       CLOB(1M),
             KOSHIN_YMD                   CHAR(8)
           ) END-EXEC.
      ******************************************************************
      * COBOL DECLARATION FOR TABLE CSQDB.BUNSHO                       *
      * NAIYOU_XML, GAZOU AND TEISAI CARRY NO HOST VARIABLE HERE;      *
      * SEE THE CONSTRUCTS COMMENT ABOVE.                              *
      ******************************************************************
       01  DCLBUNSHO.
           10  BUNSHO-ID              PIC S9(9) COMP.
           10  BUNSHO-MEI.
               49  BUNSHO-MEI-LEN     PIC S9(4) COMP.
               49  BUNSHO-MEI-TEXT    PIC X(60).
           10  KOSHIN-YMD             PIC X(8).
      ******************************************************************
      * THE NUMBER OF COLUMNS DESCRIBED BY THIS DECLARATION IS 3       *
      ******************************************************************

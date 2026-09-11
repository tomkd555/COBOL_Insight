      *----------------------------------------------------------*
      *  DCLGEN     : CSD302                                     *
      *  Constructs : DCLGEN of CSQDB.WORK_TBL; short single-    *
      *               letter column names (K, V), one nullable   *
      *               column, for MERGE/TRUNCATE/LOCK fixtures.  *
      *----------------------------------------------------------*
      ******************************************************************
      * DCLGEN TABLE(CSQDB.WORK_TBL)                                   *
      *        LIBRARY(CSQ.PROD.DCLGEN(CSD302))                        *
      *        ACTION(REPLACE)                                         *
      *        LANGUAGE(COBOL)                                         *
      *        APOST                                                   *
      *     ... IS THE DCLGEN COMMAND THAT MADE THE FOLLOWING STATEMENTS
      ******************************************************************
           EXEC SQL DECLARE CSQDB.WORK_TBL TABLE
           ( K                            CHAR(8) NOT NULL,
             V                            DECIMAL(9, 2) NOT NULL,
             KOSHIN_YMD                   CHAR(8)
           ) END-EXEC.
      ******************************************************************
      * COBOL DECLARATION FOR TABLE CSQDB.WORK_TBL                     *
      ******************************************************************
       01  DCLWORKTBL.
           10  K                      PIC X(8).
           10  V                      PIC S9(7)V9(2) COMP-3.
           10  KOSHIN-YMD             PIC X(8).
      ******************************************************************
      * THE NUMBER OF COLUMNS DESCRIBED BY THIS DECLARATION IS 3       *
      ******************************************************************

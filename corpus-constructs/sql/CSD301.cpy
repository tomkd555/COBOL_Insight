      *----------------------------------------------------------*
      *  DCLGEN     : CSD301                                     *
      *  Constructs : DCLGEN of CSQDB.HINMOKU; VARCHAR level-49    *
      *               LEN/TEXT host-variable pairs; NOT NULL and *
      *               nullable columns side by side for indicator*
      *               variable coverage on SELECT/FETCH INTO.    *
      *----------------------------------------------------------*
      ******************************************************************
      * DCLGEN TABLE(CSQDB.HINMOKU)                                    *
      *        LIBRARY(CSQ.PROD.DCLGEN(CSD301))                        *
      *        ACTION(REPLACE)                                         *
      *        LANGUAGE(COBOL)                                         *
      *        APOST                                                   *
      *     ... IS THE DCLGEN COMMAND THAT MADE THE FOLLOWING STATEMENTS
      ******************************************************************
           EXEC SQL DECLARE CSQDB.HINMOKU TABLE
           ( HINMOKU_CD                    CHAR(8) NOT NULL,
             HINMOKU_MEI                   VARCHAR(40) NOT NULL,
             TANKA                        DECIMAL(9, 2) NOT NULL,
             ZAIKO_SU                     INTEGER NOT NULL,
             BIKO                         VARCHAR(100),
             KOSHIN_YMD                   CHAR(8)
           ) END-EXEC.
      ******************************************************************
      * COBOL DECLARATION FOR TABLE CSQDB.HINMOKU                      *
      ******************************************************************
       01  DCLHINMOKU.
           10  HINMOKU-CD              PIC X(8).
           10  HINMOKU-MEI.
               49  HINMOKU-MEI-LEN     PIC S9(4) COMP.
               49  HINMOKU-MEI-TEXT    PIC X(40).
           10  TANKA                  PIC S9(7)V9(2) COMP-3.
           10  ZAIKO-SU               PIC S9(9) COMP.
           10  BIKO.
               49  BIKO-LEN           PIC S9(4) COMP.
               49  BIKO-TEXT          PIC X(100).
           10  KOSHIN-YMD             PIC X(8).
      ******************************************************************
      * THE NUMBER OF COLUMNS DESCRIBED BY THIS DECLARATION IS 6       *
      ******************************************************************

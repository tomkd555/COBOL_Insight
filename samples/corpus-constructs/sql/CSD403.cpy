      ******************************************************************
      * DCLGEN TABLE(FLDB.SHOHIN)                                      *
      *        LIBRARY(CSQ.PROD.DCLGEN(CSD403))                        *
      *        ACTION(REPLACE)                                         *
      *        LANGUAGE(COBOL)                                         *
      *        APOST                                                   *
      *     ... IS THE DCLGEN COMMAND THAT MADE THE FOLLOWING STATEMENTS
      ******************************************************************
           EXEC SQL DECLARE FLDB.SHOHIN TABLE
           ( SHOHIN_CD                      CHAR(8) NOT NULL,
             SHOHIN_MEI                     CHAR(40) NOT NULL,
             TANKA                          DECIMAL(9, 2) NOT NULL,
             BUNRUI_CD                      CHAR(4),
             HANBAI_KBN                     CHAR(1) NOT NULL
           ) END-EXEC.
      ******************************************************************
      * COBOL DECLARATION FOR TABLE FLDB.SHOHIN                        *
      ******************************************************************
       01  DCLSHOHIN.
           10 SHOHIN-CD            PIC X(8).
           10 SHOHIN-MEI           PIC X(40).
           10 TANKA                PIC S9(7)V9(2) USAGE COMP-3.
           10 BUNRUI-CD            PIC X(4).
           10 HANBAI-KBN           PIC X(1).
      ******************************************************************
      * THE NUMBER OF COLUMNS DESCRIBED BY THIS DECLARATION IS 5       *
      ******************************************************************

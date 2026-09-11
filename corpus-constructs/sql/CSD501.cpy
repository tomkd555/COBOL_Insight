      ******************************************************************
      * DCLGEN TABLE(CSDB.CSQKOZA)                                     *
      *        LIBRARY(CSQ.PROD.DCLGEN(CSD501))                        *
      *        ACTION(REPLACE)                                         *
      *        LANGUAGE(COBOL)                                         *
      *        APOST                                                   *
      *     ... IS THE DCLGEN COMMAND THAT MADE THE FOLLOWING STATEMENTS
      ******************************************************************
           EXEC SQL DECLARE CSDB.CSQKOZA TABLE
           ( KOZA_NO                        CHAR(10) NOT NULL,
             KOKYAKU_NO                     CHAR(8) NOT NULL,
             ZANDAKA                        DECIMAL(11, 0) NOT NULL,
             KOZA_MEI                       VARCHAR(30) NOT NULL,
             BIKO                           VARCHAR(60),
             KOSHIN_YMD                     CHAR(8)
           ) END-EXEC.
      ******************************************************************
      * COBOL DECLARATION FOR TABLE CSDB.CSQKOZA                       *
      ******************************************************************
       01  DCLCSQKOZA.
           10 KOZA-NO              PIC X(10).
           10 KOKYAKU-NO           PIC X(8).
           10 ZANDAKA              PIC S9(11) USAGE COMP-3.
           10 KOZA-MEI.
               49 KOZA-MEI-LEN     PIC S9(4) USAGE COMP.
               49 KOZA-MEI-TEXT    PIC X(30).
           10 BIKO.
               49 BIKO-LEN         PIC S9(4) USAGE COMP.
               49 BIKO-TEXT        PIC X(60).
           10 KOSHIN-YMD           PIC X(8).
      ******************************************************************
      * THE NUMBER OF COLUMNS DESCRIBED BY THIS DECLARATION IS 6       *
      ******************************************************************

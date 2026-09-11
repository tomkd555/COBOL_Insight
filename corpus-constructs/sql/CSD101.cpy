      ******************************************************************
      * DCLGEN TABLE(CSQDB.KOKYAKU)                                    *
      *        LIBRARY(CSQ.PROD.DCLGEN(CSD101))                        *
      *        ACTION(REPLACE)                                         *
      *        LANGUAGE(COBOL)                                         *
      *        APOST                                                   *
      *     ... IS THE DCLGEN COMMAND THAT MADE THE FOLLOWING STATEMENTS
      ******************************************************************
           EXEC SQL DECLARE CSQDB.KOKYAKU TABLE
           ( KOKYAKU_NO                     CHAR(8) NOT NULL,
             KOKYAKU_MEI                    CHAR(30) NOT NULL,
             DENWA_BANGO                    CHAR(13),
             TOROKU_YMD                     CHAR(8) NOT NULL
           ) END-EXEC.
      ******************************************************************
      * COBOL DECLARATION FOR TABLE CSQDB.KOKYAKU                      *
      ******************************************************************
       01  DCLKOKYAKU.
           10 KOKYAKU-NO           PIC X(8).
           10 KOKYAKU-MEI          PIC X(30).
           10 DENWA-BANGO          PIC X(13).
           10 TOROKU-YMD           PIC X(8).
      ******************************************************************
      * THE NUMBER OF COLUMNS DESCRIBED BY THIS DECLARATION IS 4       *
      ******************************************************************

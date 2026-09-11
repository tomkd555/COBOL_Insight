      ******************************************************************
      * DCLGEN TABLE(FLDB.KOKYAKU)                                     *
      *        LIBRARY(CSQ.PROD.DCLGEN(CSD401))                        *
      *        ACTION(REPLACE)                                         *
      *        LANGUAGE(COBOL)                                         *
      *        APOST                                                   *
      *     ... IS THE DCLGEN COMMAND THAT MADE THE FOLLOWING STATEMENTS
      ******************************************************************
           EXEC SQL DECLARE FLDB.KOKYAKU TABLE
           ( KOKYAKU_NO                     CHAR(8) NOT NULL,
             KOKYAKU_MEI                    CHAR(30) NOT NULL,
             KEN_CD                         CHAR(2),
             SHOKAI_KOKYAKU_NO              CHAR(8),
             TOROKU_YMD                     DATE NOT NULL,
             SAKUJO_FLG                     CHAR(1) NOT NULL
           ) END-EXEC.
      ******************************************************************
      * COBOL DECLARATION FOR TABLE FLDB.KOKYAKU                       *
      ******************************************************************
       01  DCLKOKYAKU.
           10 KOKYAKU-NO           PIC X(8).
           10 KOKYAKU-MEI          PIC X(30).
           10 KEN-CD               PIC X(2).
           10 SHOKAI-KOKYAKU-NO    PIC X(8).
           10 TOROKU-YMD           PIC X(10).
           10 SAKUJO-FLG           PIC X(1).
      ******************************************************************
      * THE NUMBER OF COLUMNS DESCRIBED BY THIS DECLARATION IS 6       *
      ******************************************************************

      ******************************************************************
      * DCLGEN TABLE(FLDB.JUCHU)                                       *
      *        LIBRARY(CSQ.PROD.DCLGEN(CSD402))                        *
      *        ACTION(REPLACE)                                         *
      *        LANGUAGE(COBOL)                                         *
      *        APOST                                                   *
      *     ... IS THE DCLGEN COMMAND THAT MADE THE FOLLOWING STATEMENTS
      ******************************************************************
           EXEC SQL DECLARE FLDB.JUCHU TABLE
           ( JUCHU_NO                       CHAR(10) NOT NULL,
             KOKYAKU_NO                     CHAR(8) NOT NULL,
             JUCHU_YMD                      DATE NOT NULL,
             SHOHIN_CD                      CHAR(8) NOT NULL,
             SURYO                          DECIMAL(7, 0) NOT NULL,
             KINGAKU                        DECIMAL(9, 0) NOT NULL,
             TANTO_CD                       CHAR(5)
           ) END-EXEC.
      ******************************************************************
      * COBOL DECLARATION FOR TABLE FLDB.JUCHU                         *
      ******************************************************************
       01  DCLJUCHU.
           10 JUCHU-NO             PIC X(10).
           10 KOKYAKU-NO           PIC X(8).
           10 JUCHU-YMD            PIC X(10).
           10 SHOHIN-CD            PIC X(8).
           10 SURYO                PIC S9(7) USAGE COMP-3.
           10 KINGAKU              PIC S9(9) USAGE COMP-3.
           10 TANTO-CD             PIC X(5).
      ******************************************************************
      * THE NUMBER OF COLUMNS DESCRIBED BY THIS DECLARATION IS 7       *
      ******************************************************************

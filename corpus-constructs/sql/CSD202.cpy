      ******************************************************************
      * DCLGEN TABLE(CSQDB.CHUMON)                                    *
      *        LIBRARY(CSQ.CONSTRUCTS.DCLGEN(CSD202))                 *
      *        ACTION(REPLACE)                                        *
      *        LANGUAGE(COBOL)                                        *
      *        APOST                                                  *
      *     ... IS THE DCLGEN COMMAND THAT MADE THE FOLLOWING STATEMENTS
      ******************************************************************
           EXEC SQL DECLARE CSQDB.CHUMON TABLE
           ( CHUMON_NO                      CHAR(10) NOT NULL,
             SHOHIN_CD                      CHAR(8) NOT NULL,
             SURYO                          INTEGER NOT NULL,
             CHUMON_YMD                     CHAR(8) NOT NULL,
             BIKO                           CHAR(30)
           ) END-EXEC.
      ******************************************************************
      * COBOL DECLARATION FOR TABLE CSQDB.CHUMON                      *
      ******************************************************************
       01  DCLCHUMON.
           10 CHUMON-NO            PIC X(10).
           10 SHOHIN-CD            PIC X(08).
           10 SURYO                PIC S9(09) USAGE COMP.
           10 CHUMON-YMD           PIC X(08).
           10 BIKO                 PIC X(30).
      ******************************************************************
      * THE NUMBER OF COLUMNS DESCRIBED BY THIS DECLARATION IS 5      *
      ******************************************************************

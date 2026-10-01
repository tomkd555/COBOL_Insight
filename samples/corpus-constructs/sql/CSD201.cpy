      ******************************************************************
      * DCLGEN TABLE(CSQDB.SHOHIN)                                    *
      *        LIBRARY(CSQ.CONSTRUCTS.DCLGEN(CSD201))                 *
      *        ACTION(REPLACE)                                        *
      *        LANGUAGE(COBOL)                                        *
      *        APOST                                                  *
      *     ... IS THE DCLGEN COMMAND THAT MADE THE FOLLOWING STATEMENTS
      ******************************************************************
           EXEC SQL DECLARE CSQDB.SHOHIN TABLE
           ( SHOHIN_CD                      CHAR(8) NOT NULL,
             SHOHIN_MEI                     CHAR(40) NOT NULL,
             TANKA                          DECIMAL(9, 2) NOT NULL,
             ZAIKO_SU                       INTEGER NOT NULL,
             TORIATSUKAI_KBN                CHAR(1),
             KOSHIN_YMD                     CHAR(8)
           ) END-EXEC.
      ******************************************************************
      * COBOL DECLARATION FOR TABLE CSQDB.SHOHIN                      *
      ******************************************************************
       01  DCLSHOHIN.
           10 SHOHIN-CD            PIC X(08).
           10 SHOHIN-MEI           PIC X(40).
           10 TANKA                PIC S9(7)V9(2) USAGE COMP-3.
           10 ZAIKO-SU             PIC S9(09) USAGE COMP.
           10 TORIATSUKAI-KBN      PIC X(01).
           10 KOSHIN-YMD           PIC X(08).
      ******************************************************************
      * THE NUMBER OF COLUMNS DESCRIBED BY THIS DECLARATION IS 6      *
      ******************************************************************

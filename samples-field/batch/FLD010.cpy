000100******************************************************************
000200* DCLGEN TABLE(FLDB.KEIYAKU)                                     *
000300*        LIBRARY(FL.PROD.DCLGEN(FLD010))                         *
000400*        ACTION(REPLACE)                                         *
000500*        LANGUAGE(COBOL)                                         *
000600*        APOST                                                   *
000700*     ... IS THE DCLGEN COMMAND THAT MADE THE FOLLOWING STATEMENTS
000800******************************************************************
000900     EXEC SQL DECLARE FLDB.KEIYAKU TABLE
001000     ( KEIYAKU_NO                     CHAR(10) NOT NULL,
001100       KOKYAKU_NO                     CHAR(8) NOT NULL,
001200       HANBAITEN_CD                   CHAR(5) NOT NULL,
001300       ZANDAKA                        DECIMAL(11, 0) NOT NULL,
001400       SEIKYU_GAKU                    DECIMAL(11, 0) NOT NULL,
001500       SHORI_KBN                      CHAR(1),
001600       KOSHIN_YMD                     CHAR(8)
001700     ) END-EXEC.
001800******************************************************************
001900* COBOL DECLARATION FOR TABLE FLDB.KEIYAKU                       *
002000******************************************************************
002100 01  DCLKEIYAKU.
002200     10 KEIYAKU-NO           PIC X(10).
002300     10 KOKYAKU-NO           PIC X(8).
002400     10 HANBAITEN-CD         PIC X(5).
002500     10 ZANDAKA              PIC S9(11)V USAGE COMP-3.
002600     10 SEIKYU-GAKU          PIC S9(11)V USAGE COMP-3.
002700     10 SHORI-KBN            PIC X(1).
002800     10 KOSHIN-YMD           PIC X(8).
002900******************************************************************
003000* THE NUMBER OF COLUMNS DESCRIBED BY THIS DECLARATION IS 7       *
003100******************************************************************

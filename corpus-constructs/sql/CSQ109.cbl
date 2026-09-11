      *----------------------------------------------------------------
      * CSQ109 -- constructs exercised:
      *   a program whose EXEC SQL blocks live partly in a copybook
      *   (CSC101.cpy) pulled in by a plain COBOL COPY statement,
      *   placed as the last paragraph of the PROCEDURE DIVISION.
      *----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ109.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-SHOHIN-CD                PIC X(08).
       01  WS-SHOHIN-MEI               PIC X(40).
       01  WS-TANKA                    PIC S9(07)V99 COMP-3.
           EXEC SQL END DECLARE SECTION END-EXEC.

       01  WS-PARM-AREA                PIC X(08).

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 初期化処理
           PERFORM 商品照会
           PERFORM 終了処理
           STOP RUN.

       初期化処理.
           ACCEPT WS-PARM-AREA
           MOVE WS-PARM-AREA TO WS-SHOHIN-CD
           DISPLAY 'CSQ109 商品照会を開始します。'.

       終了処理.
           EXEC SQL COMMIT END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ109 COMMIT異常 SQLCODE=' SQLCODE
           END-IF
           DISPLAY 'CSQ109 商品名 = ' WS-SHOHIN-MEI.

           COPY CSC101.

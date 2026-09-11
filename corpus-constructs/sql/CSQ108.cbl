      *----------------------------------------------------------------
      * CSQ108 -- constructs exercised:
      *   an all-DBCS Japanese data name used as a host variable
      *   (:顧客コード) in a UTF-8 program, next to a DCLGEN copybook
      *   (CSD101.cpy, pulled in by EXEC SQL INCLUDE) whose own column
      *   names are romanised. Enterprise COBOL requires a DBCS
      *   user-defined word to contain only DBCS characters, so the
      *   Japanese names here carry no single-byte prefix or hyphen.
      *----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ108.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL INCLUDE CSD101 END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  検索エリア.
           05  顧客コード             PIC X(08).
           05  電話番号               PIC X(13).
       01  電話番号指標               PIC S9(04) COMP.
           EXEC SQL END DECLARE SECTION END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           MOVE '00000010' TO 顧客コード
           PERFORM 顧客照会
           PERFORM 電話番号更新
           EXEC SQL COMMIT END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ108 COMMIT異常 SQLCODE=' SQLCODE
           END-IF
           STOP RUN.

       顧客照会.
      *    the DCLGEN structure supplies romanised elementary items;
      *    the search key here is the Japanese-named host variable
           EXEC SQL
               SELECT KOKYAKU_MEI, DENWA_BANGO, TOROKU_YMD
                 INTO :KOKYAKU-MEI,
                      :DENWA-BANGO :電話番号指標,
                      :TOROKU-YMD
                 FROM CSQDB.KOKYAKU
                WHERE KOKYAKU_NO = :顧客コード
           END-EXEC
           IF SQLCODE NOT = 0 AND SQLCODE NOT = 100
               DISPLAY 'CSQ108 SELECT異常 SQLCODE=' SQLCODE
           END-IF.

       電話番号更新.
           MOVE '0312345678' TO 電話番号
           EXEC SQL
               UPDATE CSQDB.KOKYAKU
                  SET DENWA_BANGO = :電話番号
                WHERE KOKYAKU_NO = :顧客コード
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ108 UPDATE異常 SQLCODE=' SQLCODE
           END-IF.

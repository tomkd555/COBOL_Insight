      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ503                                      *
      *  構成       : SQLコンテナ構文の検証コーパス                *
      *  対象       : EXEC SQL INCLUDE で取り込む、                *
      *               DECLARE TABLE を持たないホスト変数専用       *
      *               複写項（CSH501）。標識付きの SELECT INTO。   *
      *  用途       : 誤検出計測用（defect無し）                  *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ503.
       AUTHOR.      CSQ-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL INCLUDE CSH501 END-EXEC.

       01  WS-PARM-AREA.
           05  WS-PARM-KOZA-NO         PIC X(10).

       01  WS-ERR-MSG                  PIC X(60) VALUE SPACE.

       PROCEDURE DIVISION.
       0000-MAIN.
           ACCEPT WS-PARM-AREA
           MOVE WS-PARM-KOZA-NO TO WS-CSH-KOZA-NO
           PERFORM 1000-KOZA-SHOKAI
           STOP RUN.

       1000-KOZA-SHOKAI.
           EXEC SQL
               SELECT KOKYAKU_NO, ZANDAKA, KOSHIN_YMD
                 INTO :WS-CSH-KOKYAKU-NO,
                      :WS-CSH-ZANDAKA,
                      :WS-CSH-KOSHIN-YMD :WS-CSH-KOSHIN-YMD-IND
                 FROM CSDB.CSQKOZA
                WHERE KOZA_NO = :WS-CSH-KOZA-NO
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   PERFORM 1100-KEKKA-HYOJI
               WHEN 100
                   DISPLAY 'CSQ503 該当する口座がありません。'
               WHEN OTHER
                   MOVE '口座照会に失敗しました' TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       1100-KEKKA-HYOJI.
           IF WS-CSH-KOSHIN-YMD-IND < 0
               DISPLAY 'CSQ503 顧客番号=' WS-CSH-KOKYAKU-NO
                       ' 残高=' WS-CSH-ZANDAKA ' 更新日=未設定'
           ELSE
               DISPLAY 'CSQ503 顧客番号=' WS-CSH-KOKYAKU-NO
                       ' 残高=' WS-CSH-ZANDAKA
                       ' 更新日=' WS-CSH-KOSHIN-YMD
           END-IF.

       9900-SQL-ERROR.
           DISPLAY 'CSQ503 SQL ERROR SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-ERR-MSG
           MOVE 12 TO RETURN-CODE
           GOBACK.

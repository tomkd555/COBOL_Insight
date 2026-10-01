      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ404                                      *
      *  構成       : SQL問い合わせ構文の検証コーパス             *
      *  対象       : 共通表式 WITH T(K,V) AS(...)、              *
      *               UNION ALL による再帰共通表式                *
      *  用途       : 誤検出計測用（defect無し）                  *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ404.
       AUTHOR.      CSQ-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL INCLUDE CSD401 END-EXEC.

       01  WS-HOST-VARS.
           05  WS-KOKYAKU-NO-IN        PIC X(8)  VALUE 'K0000001'.
           05  WS-KOKYAKU-NO           PIC X(8).
           05  WS-KOKYAKU-MEI          PIC X(30).
           05  WS-GOKEI-KINGAKU        PIC S9(11)V99 USAGE COMP-3.
           05  WS-KISO-KOKYAKU-NO      PIC X(8)  VALUE 'K0000001'.
           05  WS-KAISO                PIC S9(4) COMP.

       01  WS-CONTROL.
           05  WS-ERR-MSG              PIC X(60) VALUE SPACE.
           05  WS-KEIRO-CNT            PIC 9(05) VALUE ZERO.

      *    紹介元(SHOKAI_KOKYAKU_NO)を辿る紹介経路の再帰共通表式。
      *    種となる行を起点の顧客とし、5階層先までUNION ALLで辿る。
           EXEC SQL DECLARE CSR-KEIRO CURSOR FOR
               WITH SHOKAI_KEIRO (KOKYAKU_NO, KOKYAKU_MEI, KAISO) AS
                 ( SELECT KOKYAKU_NO, KOKYAKU_MEI, 0
                     FROM FLDB.KOKYAKU
                    WHERE KOKYAKU_NO = :WS-KISO-KOKYAKU-NO
                   UNION ALL
                   SELECT C.KOKYAKU_NO, C.KOKYAKU_MEI, P.KAISO + 1
                     FROM FLDB.KOKYAKU C
                          INNER JOIN SHOKAI_KEIRO P
                                  ON C.SHOKAI_KOKYAKU_NO = P.KOKYAKU_NO
                    WHERE P.KAISO < 5 )
               SELECT KOKYAKU_NO, KOKYAKU_MEI, KAISO
                 FROM SHOKAI_KEIRO
                ORDER BY KAISO
               FOR FETCH ONLY
           END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-KOKYAKU-GOKEI
           PERFORM 2000-SHOKAI-KEIRO
           DISPLAY 'CSQ404 紹介経路件数 = ' WS-KEIRO-CNT
           STOP RUN.

      *    顧客別受注合計額を通常の共通表式で求め、指定顧客の合計額
      *    だけを取り出す（WITH T(K, V) AS(...)）。
       1000-KOKYAKU-GOKEI.
           EXEC SQL
               WITH KOKYAKU_GOKEI (KOKYAKU_NO, GOKEI_KINGAKU) AS
                 ( SELECT KOKYAKU_NO, SUM(KINGAKU)
                     FROM FLDB.JUCHU
                    GROUP BY KOKYAKU_NO )
               SELECT KOKYAKU_NO, GOKEI_KINGAKU
                 INTO :WS-KOKYAKU-NO, :WS-GOKEI-KINGAKU
                 FROM KOKYAKU_GOKEI
                WHERE KOKYAKU_NO = :WS-KOKYAKU-NO-IN
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   DISPLAY 'CSQ404 受注合計額 = ' WS-GOKEI-KINGAKU
               WHEN +100
                   DISPLAY 'CSQ404 該当する受注がありません'
               WHEN OTHER
                   MOVE '共通表式の実行に失敗しました' TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       2000-SHOKAI-KEIRO.
           EXEC SQL OPEN CSR-KEIRO END-EXEC
           IF SQLCODE NOT = 0
               MOVE '紹介経路カーソルの開始に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 2100-KEIRO-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-KEIRO END-EXEC
           IF SQLCODE NOT = 0
               MOVE '紹介経路カーソルの終了に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       2100-KEIRO-FETCH.
           EXEC SQL
               FETCH CSR-KEIRO
                INTO :WS-KOKYAKU-NO, :WS-KOKYAKU-MEI, :WS-KAISO
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-KEIRO-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE '紹介経路カーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       9900-SQL-ERROR.
           DISPLAY 'CSQ404 SQL ERROR SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-ERR-MSG
           MOVE 12 TO RETURN-CODE
           GOBACK.

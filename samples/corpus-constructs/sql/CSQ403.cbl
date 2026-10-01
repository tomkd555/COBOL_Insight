      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ403                                      *
      *  構成       : SQL問い合わせ構文の検証コーパス             *
      *  対象       : UNION、UNION ALL、EXCEPT、INTERSECT         *
      *  用途       : 誤検出計測用（defect無し）                  *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ403.
       AUTHOR.      CSQ-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL INCLUDE CSD401 END-EXEC.

       01  WS-HOST-VARS.
           05  WS-KOKYAKU-NO           PIC X(8).

       01  WS-CONTROL.
           05  WS-ERR-MSG              PIC X(60) VALUE SPACE.
           05  WS-UNION-CNT            PIC 9(05) VALUE ZERO.
           05  WS-UNIONALL-CNT         PIC 9(05) VALUE ZERO.
           05  WS-EXCEPT-CNT           PIC 9(05) VALUE ZERO.
           05  WS-INTERSECT-CNT        PIC 9(05) VALUE ZERO.

      *    2020年より前に登録した顧客と東京都(13)の顧客を重複なく
      *    一覧化する（UNION）。
           EXEC SQL DECLARE CSR-UNION CURSOR FOR
               SELECT KOKYAKU_NO FROM FLDB.KOKYAKU
                WHERE TOROKU_YMD < '2020-01-01'
               UNION
               SELECT KOKYAKU_NO FROM FLDB.KOKYAKU
                WHERE KEN_CD = '13'
               FOR FETCH ONLY
           END-EXEC.

      *    東京都(13)と神奈川県(14)の顧客を重複を残したまま一覧化
      *    する（UNION ALL）。
           EXEC SQL DECLARE CSR-UNIONALL CURSOR FOR
               SELECT KOKYAKU_NO FROM FLDB.KOKYAKU
                WHERE KEN_CD = '13'
               UNION ALL
               SELECT KOKYAKU_NO FROM FLDB.KOKYAKU
                WHERE KEN_CD = '14'
               FOR FETCH ONLY
           END-EXEC.

      *    誰の紹介元にもなっていない顧客を一覧化する（EXCEPT）。
           EXEC SQL DECLARE CSR-EXCEPT CURSOR FOR
               SELECT KOKYAKU_NO FROM FLDB.KOKYAKU
               EXCEPT
               SELECT SHOKAI_KOKYAKU_NO FROM FLDB.KOKYAKU
                WHERE SHOKAI_KOKYAKU_NO IS NOT NULL
               FOR FETCH ONLY
           END-EXEC.

      *    東京都(13)の顧客のうち受注実績もある顧客を一覧化する
      *    （INTERSECT）。
           EXEC SQL DECLARE CSR-INTERSECT CURSOR FOR
               SELECT KOKYAKU_NO FROM FLDB.KOKYAKU
                WHERE KEN_CD = '13'
               INTERSECT
               SELECT KOKYAKU_NO FROM FLDB.JUCHU
               FOR FETCH ONLY
           END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-UNION
           PERFORM 2000-UNION-ALL
           PERFORM 3000-EXCEPT
           PERFORM 4000-INTERSECT
           DISPLAY 'CSQ403 UNION=' WS-UNION-CNT
                   ' UNION ALL=' WS-UNIONALL-CNT
           DISPLAY 'CSQ403 EXCEPT=' WS-EXCEPT-CNT
                   ' INTERSECT=' WS-INTERSECT-CNT
           STOP RUN.

       1000-UNION.
           EXEC SQL OPEN CSR-UNION END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'UNIONカーソルの開始に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 1100-UNION-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-UNION END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'UNIONカーソルの終了に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       1100-UNION-FETCH.
           EXEC SQL FETCH CSR-UNION INTO :WS-KOKYAKU-NO END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-UNION-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE 'UNIONカーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       2000-UNION-ALL.
           EXEC SQL OPEN CSR-UNIONALL END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'UNION ALLカーソルの開始に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 2100-UNION-ALL-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-UNIONALL END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'UNION ALLカーソルの終了に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       2100-UNION-ALL-FETCH.
           EXEC SQL FETCH CSR-UNIONALL INTO :WS-KOKYAKU-NO END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-UNIONALL-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE 'UNION ALLカーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       3000-EXCEPT.
           EXEC SQL OPEN CSR-EXCEPT END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'EXCEPTカーソルの開始に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 3100-EXCEPT-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-EXCEPT END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'EXCEPTカーソルの終了に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       3100-EXCEPT-FETCH.
           EXEC SQL FETCH CSR-EXCEPT INTO :WS-KOKYAKU-NO END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-EXCEPT-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE 'EXCEPTカーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       4000-INTERSECT.
           EXEC SQL OPEN CSR-INTERSECT END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'INTERSECTカーソルの開始に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 4100-INTERSECT-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-INTERSECT END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'INTERSECTカーソルの終了に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       4100-INTERSECT-FETCH.
           EXEC SQL FETCH CSR-INTERSECT INTO :WS-KOKYAKU-NO END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-INTERSECT-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE 'INTERSECTカーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       9900-SQL-ERROR.
           DISPLAY 'CSQ403 SQL ERROR SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-ERR-MSG
           MOVE 12 TO RETURN-CODE
           GOBACK.

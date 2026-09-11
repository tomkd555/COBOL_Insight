      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ407                                      *
      *  構成       : SQL問い合わせ構文の検証コーパス             *
      *  対象       : GROUP BY の GROUPING SETS・ROLLUP・CUBE、   *
      *               HAVING、式・序数によるORDER BY、DISTINCT、  *
      *               FROM句の入れ子表式とTABLE(...)表参照、      *
      *               ESCAPE付きLIKE、BETWEEN、16進定数X'C1C2'    *
      *  用途       : 誤検出計測用（defect無し）                  *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ407.
       AUTHOR.      CSQ-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL INCLUDE CSD403 END-EXEC.

       01  WS-HOST-VARS.
           05  WS-KOKYAKU-NO-DISP      PIC X(10).
           05  WS-SHOHIN-CD-DISP       PIC X(10).
           05  WS-GOKEI-KINGAKU        PIC S9(11)V99 USAGE COMP-3.
           05  WS-GOKEI-KINGAKU-IND    PIC S9(4) COMP.
           05  WS-KEN-CD-DISP          PIC X(10).
           05  WS-SAKUJO-FLG-DISP      PIC X(10).
           05  WS-KOKYAKU-CNT          PIC S9(9) COMP.
           05  WS-KEN-CD               PIC X(2).
           05  WS-KEN-CD-IND           PIC S9(4) COMP.
           05  WS-SHOHIN-CD            PIC X(8).
           05  WS-JUCHU-KENSU          PIC S9(9) COMP.
           05  WS-SHOHIN-MEI           PIC X(40).
           05  WS-TANKA                PIC S9(7)V9(2) USAGE COMP-3.
           05  WS-BUNRUI-CD            PIC X(4).
           05  WS-BUNRUI-CD-IND        PIC S9(4) COMP.

       01  WS-CONTROL.
           05  WS-ERR-MSG              PIC X(60) VALUE SPACE.
           05  WS-ROLLUP-CNT           PIC 9(05) VALUE ZERO.
           05  WS-CUBE-CNT             PIC 9(05) VALUE ZERO.
           05  WS-GSETS-CNT            PIC 9(05) VALUE ZERO.
           05  WS-DISTINCT-CNT         PIC 9(05) VALUE ZERO.
           05  WS-DERIVED-CNT          PIC 9(05) VALUE ZERO.
           05  WS-LIKE-CNT             PIC 9(05) VALUE ZERO.

      *    顧客・商品別受注額をROLLUPで小計・総計付きに集計する。
      *    小計行はNULLになるためCOALESCEで表示用に置き換える。
           EXEC SQL DECLARE CSR-ROLLUP CURSOR FOR
               SELECT COALESCE(KOKYAKU_NO, '合計'),
                      COALESCE(SHOHIN_CD, '合計'),
                      SUM(KINGAKU) AS GOKEI
                 FROM FLDB.JUCHU
                GROUP BY ROLLUP(KOKYAKU_NO, SHOHIN_CD)
               HAVING SUM(KINGAKU) > 0
                ORDER BY 3 DESC, COALESCE(KOKYAKU_NO, '合計')
               FOR FETCH ONLY
           END-EXEC.

      *    都道府県コードと削除区分の全組み合わせをCUBEで集計する。
           EXEC SQL DECLARE CSR-CUBE CURSOR FOR
               SELECT COALESCE(KEN_CD, '合計'),
                      COALESCE(SAKUJO_FLG, '合計'),
                      COUNT(*) AS KOKYAKU_CNT
                 FROM FLDB.KOKYAKU
                GROUP BY CUBE(KEN_CD, SAKUJO_FLG)
               FOR FETCH ONLY
           END-EXEC.

      *    顧客別集計・商品別集計・全体集計をGROUPING SETSで一度に
      *    求める。
           EXEC SQL DECLARE CSR-GSETS CURSOR FOR
               SELECT COALESCE(KOKYAKU_NO, '合計'),
                      COALESCE(SHOHIN_CD, '合計'),
                      SUM(KINGAKU) AS GOKEI
                 FROM FLDB.JUCHU
                GROUP BY GROUPING SETS ((KOKYAKU_NO), (SHOHIN_CD), ())
               FOR FETCH ONLY
           END-EXEC.

      *    有効な顧客が住む都道府県コードを重複なく一覧化する
      *    （DISTINCT）。
           EXEC SQL DECLARE CSR-DISTINCT CURSOR FOR
               SELECT DISTINCT KEN_CD
                 FROM FLDB.KOKYAKU
                WHERE SAKUJO_FLG = '0'
               FOR FETCH ONLY
           END-EXEC.

      *    商品別受注金額合計をFROM句の入れ子表式(UA)で求め、
      *    商品別受注件数をTABLE(...)表参照(UB)で求めて結合する。
           EXEC SQL DECLARE CSR-DERIVED CURSOR FOR
               SELECT UA.SHOHIN_CD, UA.GOKEI, UB.JUCHU_KENSU
                 FROM ( SELECT SHOHIN_CD, SUM(KINGAKU) AS GOKEI
                          FROM FLDB.JUCHU
                         GROUP BY SHOHIN_CD ) AS UA
                      INNER JOIN
                      TABLE ( SELECT SHOHIN_CD, COUNT(*) AS JUCHU_KENSU
                                FROM FLDB.JUCHU
                               GROUP BY SHOHIN_CD ) AS UB
                              ON UA.SHOHIN_CD = UB.SHOHIN_CD
               FOR FETCH ONLY
           END-EXEC.

      *    商品名の一部一致(ESCAPE付きLIKE)、単価の範囲(BETWEEN)、
      *    分類コードの16進定数比較を組み合わせた検索カーソル。
           EXEC SQL DECLARE CSR-LIKE CURSOR FOR
               SELECT SHOHIN_CD, SHOHIN_MEI, TANKA, BUNRUI_CD
                 FROM FLDB.SHOHIN
                WHERE SHOHIN_MEI LIKE 'A\_%' ESCAPE '\'
                  AND TANKA BETWEEN 100 AND 100000
                  AND BUNRUI_CD = X'C1C2'
               FOR FETCH ONLY
           END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-ROLLUP
           PERFORM 2000-CUBE
           PERFORM 3000-GROUPING-SETS
           PERFORM 4000-DISTINCT
           PERFORM 5000-DERIVED
           PERFORM 6000-LIKE-BETWEEN-HEX
           DISPLAY 'CSQ407 ROLLUP=' WS-ROLLUP-CNT
                   ' CUBE=' WS-CUBE-CNT
           DISPLAY 'CSQ407 GSETS=' WS-GSETS-CNT
                   ' DISTINCT=' WS-DISTINCT-CNT
           DISPLAY 'CSQ407 DERIVED=' WS-DERIVED-CNT
                   ' LIKE=' WS-LIKE-CNT
           STOP RUN.

       1000-ROLLUP.
           EXEC SQL OPEN CSR-ROLLUP END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'ROLLUPカーソルの開始に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 1100-ROLLUP-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-ROLLUP END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'ROLLUPカーソルの終了に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       1100-ROLLUP-FETCH.
           EXEC SQL
               FETCH CSR-ROLLUP
                INTO :WS-KOKYAKU-NO-DISP, :WS-SHOHIN-CD-DISP,
                     :WS-GOKEI-KINGAKU
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-ROLLUP-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE 'ROLLUPカーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       2000-CUBE.
           EXEC SQL OPEN CSR-CUBE END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'CUBEカーソルの開始に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 2100-CUBE-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-CUBE END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'CUBEカーソルの終了に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       2100-CUBE-FETCH.
           EXEC SQL
               FETCH CSR-CUBE
                INTO :WS-KEN-CD-DISP, :WS-SAKUJO-FLG-DISP,
                     :WS-KOKYAKU-CNT
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-CUBE-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE 'CUBEカーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       3000-GROUPING-SETS.
           EXEC SQL OPEN CSR-GSETS END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'GROUPING SETSカーソルの開始に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 3100-GSETS-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-GSETS END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'GROUPING SETSカーソルの終了に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       3100-GSETS-FETCH.
           EXEC SQL
               FETCH CSR-GSETS
                INTO :WS-KOKYAKU-NO-DISP, :WS-SHOHIN-CD-DISP,
                     :WS-GOKEI-KINGAKU :WS-GOKEI-KINGAKU-IND
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-GSETS-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE 'GROUPING SETSカーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       4000-DISTINCT.
           EXEC SQL OPEN CSR-DISTINCT END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'DISTINCTカーソルの開始に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 4100-DISTINCT-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-DISTINCT END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'DISTINCTカーソルの終了に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       4100-DISTINCT-FETCH.
           EXEC SQL
               FETCH CSR-DISTINCT
                INTO :WS-KEN-CD :WS-KEN-CD-IND
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-DISTINCT-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE 'DISTINCTカーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       5000-DERIVED.
           EXEC SQL OPEN CSR-DERIVED END-EXEC
           IF SQLCODE NOT = 0
               MOVE '入れ子表式カーソルの開始に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 5100-DERIVED-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-DERIVED END-EXEC
           IF SQLCODE NOT = 0
               MOVE '入れ子表式カーソルの終了に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       5100-DERIVED-FETCH.
           EXEC SQL
               FETCH CSR-DERIVED
                INTO :WS-SHOHIN-CD, :WS-GOKEI-KINGAKU, :WS-JUCHU-KENSU
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-DERIVED-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE '入れ子表式カーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       6000-LIKE-BETWEEN-HEX.
           EXEC SQL OPEN CSR-LIKE END-EXEC
           IF SQLCODE NOT = 0
               MOVE '商品検索カーソルの開始に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 6100-LIKE-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-LIKE END-EXEC
           IF SQLCODE NOT = 0
               MOVE '商品検索カーソルの終了に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       6100-LIKE-FETCH.
           EXEC SQL
               FETCH CSR-LIKE
                INTO :WS-SHOHIN-CD, :WS-SHOHIN-MEI, :WS-TANKA,
                     :WS-BUNRUI-CD :WS-BUNRUI-CD-IND
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-LIKE-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE '商品検索カーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       9900-SQL-ERROR.
           DISPLAY 'CSQ407 SQL ERROR SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-ERR-MSG
           MOVE 12 TO RETURN-CODE
           GOBACK.

      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ405                                      *
      *  構成       : SQL問い合わせ構文の検証コーパス             *
      *  対象       : ROW_NUMBER()・RANK()・DENSE_RANK()の         *
      *               OVER(PARTITION BY...ORDER BY...)、          *
      *               単純CASE・探索CASE、SELECT句のスカラー      *
      *               フルセレクト                                *
      *  用途       : 誤検出計測用（defect無し）                  *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ405.
       AUTHOR.      CSQ-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL INCLUDE CSD402 END-EXEC.

       01  WS-HOST-VARS.
           05  WS-JUCHU-NO             PIC X(10).
           05  WS-KOKYAKU-NO           PIC X(8).
           05  WS-KINGAKU              PIC S9(9) USAGE COMP-3.
           05  WS-JUN-NO               PIC S9(18) COMP.
           05  WS-RANK-NO              PIC S9(18) COMP.
           05  WS-DRANK-NO             PIC S9(18) COMP.
           05  WS-KUBUN                PIC X(10).
           05  WS-TANTO-MEI            PIC X(12).
           05  WS-SHOHIN-MEI-KEKKA     PIC X(40).
           05  WS-SHOHIN-MEI-KEKKA-IND PIC S9(4) COMP.

       01  WS-CONTROL.
           05  WS-ERR-MSG              PIC X(60) VALUE SPACE.
           05  WS-RANK-CNT             PIC 9(05) VALUE ZERO.

      *    顧客ごとの受注額の順位(ROW_NUMBER・RANK・DENSE_RANK)、
      *    受注額区分と担当者名の単純・探索CASE、商品名を求める
      *    SELECT句のスカラーフルセレクトを一度に列挙する。
           EXEC SQL DECLARE CSR-RANK CURSOR FOR
               SELECT J.JUCHU_NO, J.KOKYAKU_NO, J.KINGAKU,
                      ROW_NUMBER() OVER (PARTITION BY J.KOKYAKU_NO
                                         ORDER BY J.KINGAKU DESC)
                          AS JUN_NO,
                      RANK() OVER (PARTITION BY J.KOKYAKU_NO
                                   ORDER BY J.KINGAKU DESC)
                          AS RANK_NO,
                      DENSE_RANK() OVER (PARTITION BY J.KOKYAKU_NO
                                         ORDER BY J.KINGAKU DESC)
                          AS DRANK_NO,
                      CASE
                          WHEN J.KINGAKU >= 100000 THEN '大口'
                          WHEN J.KINGAKU >= 10000  THEN '中口'
                          ELSE '小口'
                      END AS KUBUN,
                      CASE J.TANTO_CD
                          WHEN 'T0001' THEN '東京担当'
                          WHEN 'T0002' THEN '大阪担当'
                          ELSE '未定'
                      END AS TANTO_MEI,
                      ( SELECT S.SHOHIN_MEI
                          FROM FLDB.SHOHIN S
                         WHERE S.SHOHIN_CD = J.SHOHIN_CD )
                          AS SHOHIN_MEI_KEKKA
                 FROM FLDB.JUCHU J
                FOR FETCH ONLY
           END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-RANK
           DISPLAY 'CSQ405 順位付け件数 = ' WS-RANK-CNT
           STOP RUN.

       1000-RANK.
           EXEC SQL OPEN CSR-RANK END-EXEC
           IF SQLCODE NOT = 0
               MOVE '順位付けカーソルの開始に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 1100-RANK-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-RANK END-EXEC
           IF SQLCODE NOT = 0
               MOVE '順位付けカーソルの終了に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       1100-RANK-FETCH.
           EXEC SQL
               FETCH CSR-RANK
                INTO :WS-JUCHU-NO, :WS-KOKYAKU-NO, :WS-KINGAKU,
                     :WS-JUN-NO, :WS-RANK-NO, :WS-DRANK-NO,
                     :WS-KUBUN, :WS-TANTO-MEI,
                     :WS-SHOHIN-MEI-KEKKA :WS-SHOHIN-MEI-KEKKA-IND
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-RANK-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE '順位付けカーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       9900-SQL-ERROR.
           DISPLAY 'CSQ405 SQL ERROR SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-ERR-MSG
           MOVE 12 TO RETURN-CODE
           GOBACK.

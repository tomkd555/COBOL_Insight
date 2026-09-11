      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ401                                      *
      *  構成       : SQL問い合わせ構文の検証コーパス             *
      *  対象       : INNER JOIN、LEFT OUTER JOIN、               *
      *               RIGHT OUTER JOIN、FULL OUTER JOIN（ON句）   *
      *  用途       : 誤検出計測用（defect無し）                  *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ401.
       AUTHOR.      CSQ-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL INCLUDE CSD401 END-EXEC.
           EXEC SQL INCLUDE CSD402 END-EXEC.
           EXEC SQL INCLUDE CSD403 END-EXEC.

       01  WS-HOST-VARS.
           05  WS-JUCHU-NO             PIC X(10).
           05  WS-JUCHU-NO-IND         PIC S9(4) COMP.
           05  WS-JUCHU-YMD            PIC X(10).
           05  WS-JUCHU-YMD-IND        PIC S9(4) COMP.
           05  WS-KOKYAKU-NO           PIC X(8).
           05  WS-KOKYAKU-MEI          PIC X(30).
           05  WS-SHOHIN-CD            PIC X(8).
           05  WS-SHOHIN-MEI           PIC X(40).
           05  WS-KINGAKU              PIC S9(9) USAGE COMP-3.
           05  WS-KINGAKU-IND          PIC S9(4) COMP.
           05  WS-TANTO-CD             PIC X(5).
           05  WS-TANTO-CD-IND         PIC S9(4) COMP.
           05  WS-A-KOKYAKU-NO         PIC X(8).
           05  WS-A-KOKYAKU-NO-IND     PIC S9(4) COMP.
           05  WS-A-KOKYAKU-MEI        PIC X(30).
           05  WS-A-KOKYAKU-MEI-IND    PIC S9(4) COMP.
           05  WS-B-KOKYAKU-NO         PIC X(8).
           05  WS-B-KOKYAKU-NO-IND     PIC S9(4) COMP.
           05  WS-B-KOKYAKU-MEI        PIC X(30).
           05  WS-B-KOKYAKU-MEI-IND    PIC S9(4) COMP.
           05  WS-KEN-CD-IN            PIC X(2) VALUE '13'.

       01  WS-CONTROL.
           05  WS-ERR-MSG              PIC X(60) VALUE SPACE.
           05  WS-INNER-CNT            PIC 9(05) VALUE ZERO.
           05  WS-LEFT-CNT             PIC 9(05) VALUE ZERO.
           05  WS-RIGHT-CNT            PIC 9(05) VALUE ZERO.
           05  WS-FULL-CNT             PIC 9(05) VALUE ZERO.

      *    受注に担当者(TANTO_CD)が設定されている行だけを対象にした
      *    内部結合カーソル。受注・顧客・商品の3表を結合する。
           EXEC SQL DECLARE CSR-INNER CURSOR FOR
               SELECT J.JUCHU_NO, J.JUCHU_YMD, J.KOKYAKU_NO,
                      K.KOKYAKU_MEI, J.SHOHIN_CD, S.SHOHIN_MEI,
                      J.KINGAKU, J.TANTO_CD
                 FROM FLDB.JUCHU J
                      INNER JOIN FLDB.KOKYAKU K
                              ON J.KOKYAKU_NO = K.KOKYAKU_NO
                      INNER JOIN FLDB.SHOHIN S
                              ON J.SHOHIN_CD = S.SHOHIN_CD
                WHERE J.TANTO_CD IS NOT NULL
                FOR FETCH ONLY
           END-EXEC.

      *    顧客ごとに受注の有無を問わず一覧化する左外部結合カーソル。
      *    受注のない顧客では受注側の列がすべてNULLになる。
           EXEC SQL DECLARE CSR-LEFT CURSOR FOR
               SELECT K.KOKYAKU_NO, K.KOKYAKU_MEI,
                      J.JUCHU_NO, J.JUCHU_YMD, J.KINGAKU
                 FROM FLDB.KOKYAKU K
                      LEFT OUTER JOIN FLDB.JUCHU J
                                   ON K.KOKYAKU_NO = J.KOKYAKU_NO
                WHERE K.SAKUJO_FLG = '0'
                FOR FETCH ONLY
           END-EXEC.

      *    商品ごとに受注実績の有無を問わず一覧化する右外部結合カー
      *    ソル。FLDB.JUCHUを左辺に置いたまま右辺のFLDB.SHOHINを
      *    すべて残す。
           EXEC SQL DECLARE CSR-RIGHT CURSOR FOR
               SELECT S.SHOHIN_CD, S.SHOHIN_MEI,
                      J.JUCHU_NO, J.KINGAKU
                 FROM FLDB.JUCHU J
                      RIGHT OUTER JOIN FLDB.SHOHIN S
                                    ON J.SHOHIN_CD = S.SHOHIN_CD
                WHERE S.HANBAI_KBN = '1'
                FOR FETCH ONLY
           END-EXEC.

      *    紹介元(SHOKAI_KOKYAKU_NO)で顧客表を自己結合し、どちらか
      *    一方にしか現れない行も残す完全外部結合カーソル。
           EXEC SQL DECLARE CSR-FULL CURSOR FOR
               SELECT A.KOKYAKU_NO, A.KOKYAKU_MEI,
                      B.KOKYAKU_NO, B.KOKYAKU_MEI
                 FROM FLDB.KOKYAKU A
                      FULL OUTER JOIN FLDB.KOKYAKU B
                                   ON A.SHOKAI_KOKYAKU_NO = B.KOKYAKU_NO
                WHERE A.KEN_CD = :WS-KEN-CD-IN
                FOR FETCH ONLY
           END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-INNER-JOIN
           PERFORM 2000-LEFT-JOIN
           PERFORM 3000-RIGHT-JOIN
           PERFORM 4000-FULL-JOIN
           DISPLAY 'CSQ401 INNER=' WS-INNER-CNT
                   ' LEFT=' WS-LEFT-CNT
           DISPLAY 'CSQ401 RIGHT=' WS-RIGHT-CNT
                   ' FULL=' WS-FULL-CNT
           STOP RUN.

       1000-INNER-JOIN.
           EXEC SQL OPEN CSR-INNER END-EXEC
           IF SQLCODE NOT = 0
               MOVE '内部結合カーソルの開始に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 1100-INNER-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-INNER END-EXEC
           IF SQLCODE NOT = 0
               MOVE '内部結合カーソルの終了に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       1100-INNER-FETCH.
           EXEC SQL
               FETCH CSR-INNER
                INTO :WS-JUCHU-NO, :WS-JUCHU-YMD, :WS-KOKYAKU-NO,
                     :WS-KOKYAKU-MEI, :WS-SHOHIN-CD, :WS-SHOHIN-MEI,
                     :WS-KINGAKU, :WS-TANTO-CD :WS-TANTO-CD-IND
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-INNER-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE '内部結合カーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       2000-LEFT-JOIN.
           EXEC SQL OPEN CSR-LEFT END-EXEC
           IF SQLCODE NOT = 0
               MOVE '左外部結合カーソルの開始に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 2100-LEFT-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-LEFT END-EXEC
           IF SQLCODE NOT = 0
               MOVE '左外部結合カーソルの終了に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       2100-LEFT-FETCH.
           EXEC SQL
               FETCH CSR-LEFT
                INTO :WS-KOKYAKU-NO, :WS-KOKYAKU-MEI,
                     :WS-JUCHU-NO :WS-JUCHU-NO-IND,
                     :WS-JUCHU-YMD :WS-JUCHU-YMD-IND,
                     :WS-KINGAKU :WS-KINGAKU-IND
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-LEFT-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE '左外部結合カーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       3000-RIGHT-JOIN.
           EXEC SQL OPEN CSR-RIGHT END-EXEC
           IF SQLCODE NOT = 0
               MOVE '右外部結合カーソルの開始に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 3100-RIGHT-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-RIGHT END-EXEC
           IF SQLCODE NOT = 0
               MOVE '右外部結合カーソルの終了に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       3100-RIGHT-FETCH.
           EXEC SQL
               FETCH CSR-RIGHT
                INTO :WS-SHOHIN-CD, :WS-SHOHIN-MEI,
                     :WS-JUCHU-NO :WS-JUCHU-NO-IND,
                     :WS-KINGAKU :WS-KINGAKU-IND
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-RIGHT-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE '右外部結合カーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       4000-FULL-JOIN.
           EXEC SQL OPEN CSR-FULL END-EXEC
           IF SQLCODE NOT = 0
               MOVE '完全外部結合カーソルの開始に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 4100-FULL-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-FULL END-EXEC
           IF SQLCODE NOT = 0
               MOVE '完全外部結合カーソルの終了に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       4100-FULL-FETCH.
           EXEC SQL
               FETCH CSR-FULL
                INTO :WS-A-KOKYAKU-NO :WS-A-KOKYAKU-NO-IND,
                     :WS-A-KOKYAKU-MEI :WS-A-KOKYAKU-MEI-IND,
                     :WS-B-KOKYAKU-NO :WS-B-KOKYAKU-NO-IND,
                     :WS-B-KOKYAKU-MEI :WS-B-KOKYAKU-MEI-IND
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-FULL-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE '完全外部結合カーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       9900-SQL-ERROR.
           DISPLAY 'CSQ401 SQL ERROR SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-ERR-MSG
           MOVE 12 TO RETURN-CODE
           GOBACK.

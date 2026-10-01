      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ402                                      *
      *  構成       : SQL問い合わせ構文の検証コーパス             *
      *  対象       : 相関副問合せ、EXISTS、NOT EXISTS、          *
      *               IN(副問合せ)、= ANY、> ALL                 *
      *  用途       : 誤検出計測用（defect無し）                  *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ402.
       AUTHOR.      CSQ-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL INCLUDE CSD402 END-EXEC.

       01  WS-HOST-VARS.
           05  WS-CNT                  PIC S9(9) COMP.
           05  WS-SHOHIN-CD-IN         PIC X(8)  VALUE 'S0000001'.
           05  WS-SHOHIN-CD-IN2        PIC X(8)  VALUE 'S0000002'.

       01  WS-ERR-MSG                  PIC X(60) VALUE SPACE.

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-CORRELATED-SUBSELECT
           PERFORM 2000-EXISTS
           PERFORM 3000-NOT-EXISTS
           PERFORM 4000-IN-SUBSELECT
           PERFORM 5000-EQUAL-ANY
           PERFORM 6000-GREATER-ALL
           STOP RUN.

      *    自受注の平均額を上回る受注件数（相関副問合せ、J1に対する
      *    J2の相関）。
       1000-CORRELATED-SUBSELECT.
           EXEC SQL
               SELECT COUNT(*)
                 INTO :WS-CNT
                 FROM FLDB.JUCHU J1
                WHERE J1.KINGAKU >
                          ( SELECT AVG(J2.KINGAKU)
                              FROM FLDB.JUCHU J2
                             WHERE J2.KOKYAKU_NO = J1.KOKYAKU_NO )
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '相関副問合せの実行に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           DISPLAY 'CSQ402 相関副問合せ件数 = ' WS-CNT.

      *    受注が一件以上ある顧客数（EXISTS）。
       2000-EXISTS.
           EXEC SQL
               SELECT COUNT(*)
                 INTO :WS-CNT
                 FROM FLDB.KOKYAKU K
                WHERE EXISTS
                          ( SELECT 1
                              FROM FLDB.JUCHU J
                             WHERE J.KOKYAKU_NO = K.KOKYAKU_NO )
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'EXISTSの実行に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           DISPLAY 'CSQ402 受注あり顧客数 = ' WS-CNT.

      *    受注が一件もない顧客数（NOT EXISTS）。
       3000-NOT-EXISTS.
           EXEC SQL
               SELECT COUNT(*)
                 INTO :WS-CNT
                 FROM FLDB.KOKYAKU K
                WHERE NOT EXISTS
                          ( SELECT 1
                              FROM FLDB.JUCHU J
                             WHERE J.KOKYAKU_NO = K.KOKYAKU_NO )
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'NOT EXISTSの実行に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           DISPLAY 'CSQ402 受注なし顧客数 = ' WS-CNT.

      *    一度でも受注された商品数（IN(副問合せ)）。
       4000-IN-SUBSELECT.
           EXEC SQL
               SELECT COUNT(*)
                 INTO :WS-CNT
                 FROM FLDB.SHOHIN S
                WHERE S.SHOHIN_CD IN
                          ( SELECT J.SHOHIN_CD
                              FROM FLDB.JUCHU J )
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'IN(副問合せ)の実行に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           DISPLAY 'CSQ402 受注実績のある商品数 = ' WS-CNT.

      *    指定商品の受注額のいずれかと一致する受注件数（= ANY）。
       5000-EQUAL-ANY.
           EXEC SQL
               SELECT COUNT(*)
                 INTO :WS-CNT
                 FROM FLDB.JUCHU J
                WHERE J.KINGAKU = ANY
                          ( SELECT J2.KINGAKU
                              FROM FLDB.JUCHU J2
                             WHERE J2.SHOHIN_CD = :WS-SHOHIN-CD-IN )
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '= ANYの実行に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           DISPLAY 'CSQ402 一致する受注件数 = ' WS-CNT.

      *    指定商品の受注額の全件を上回る受注件数（> ALL）。
       6000-GREATER-ALL.
           EXEC SQL
               SELECT COUNT(*)
                 INTO :WS-CNT
                 FROM FLDB.JUCHU J
                WHERE J.KINGAKU > ALL
                          ( SELECT J2.KINGAKU
                              FROM FLDB.JUCHU J2
                             WHERE J2.SHOHIN_CD = :WS-SHOHIN-CD-IN2 )
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '> ALLの実行に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           DISPLAY 'CSQ402 全件を上回る受注件数 = ' WS-CNT.

       9900-SQL-ERROR.
           DISPLAY 'CSQ402 SQL ERROR SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-ERR-MSG
           MOVE 12 TO RETURN-CODE
           GOBACK.

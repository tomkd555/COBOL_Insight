      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ408                                      *
      *  構成       : SQL問い合わせ構文の検証コーパス             *
      *  対象       : NEXT VALUE FOR・PREVIOUS VALUE FOR、        *
      *               CURRENT DATE・TIME・TIMESTAMP・TIMEZONE・   *
      *               SERVER・SQLID、ROW CHANGE TIMESTAMP FOR、   *
      *               FOR SYSTEM_TIME AS OF・FOR BUSINESS_TIME    *
      *               AS OF、OFFSET...FETCH NEXT...ROWS ONLY、    *
      *               JSON_VAL、FETCH FIRST・OPTIMIZE FOR・       *
      *               WITH URの併用                               *
      *  用途       : 誤検出計測用（defect無し）                  *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ408.
       AUTHOR.      CSQ-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL INCLUDE CSD401 END-EXEC.
           EXEC SQL INCLUDE CSD403 END-EXEC.

       01  WS-SEQ-VARS.
           05  WS-NEXT-SEQ             PIC S9(18) COMP.
           05  WS-PREV-SEQ             PIC S9(18) COMP.

       01  WS-REGISTER-VARS.
           05  WS-C-DATE               PIC X(10).
           05  WS-C-TIME               PIC X(8).
           05  WS-C-TS                 PIC X(26).
           05  WS-C-TZ                 PIC S9(6) USAGE COMP-3.
           05  WS-C-SERVER             PIC X(24).
           05  WS-C-SQLID              PIC X(8).

       01  WS-TEMPORAL-VARS.
           05  WS-KOKYAKU-NO-IN        PIC X(8)  VALUE 'K0000001'.
           05  WS-SYSTEM-TS            PIC X(26)
               VALUE '2026-09-10-00.00.00.000000'.
           05  WS-ROW-CHANGE-TS        PIC X(26).
           05  WS-BUSINESS-TS          PIC X(10) VALUE '2026-09-10'.
           05  WS-KOKYAKU-MEI-BT       PIC X(30).

       01  WS-PAGE-VARS.
           05  WS-KOKYAKU-NO-PAGE      PIC X(8).
           05  WS-KOKYAKU-MEI-PAGE     PIC X(30).

       01  WS-JSON-VARS.
           05  WS-JSON-TEXT            PIC X(200)
               VALUE '{"a":{"b":"sample"}}'.
           05  WS-JSON-RESULT          PIC X(32).

       01  WS-TOPN-VARS.
           05  WS-SHOHIN-CD-TOPN       PIC X(8).
           05  WS-SHOHIN-MEI-TOPN      PIC X(40).
           05  WS-TANKA-TOPN           PIC S9(7)V9(2) USAGE COMP-3.

       01  WS-CONTROL.
           05  WS-ERR-MSG              PIC X(60) VALUE SPACE.
           05  WS-PAGE-CNT             PIC 9(05) VALUE ZERO.
           05  WS-TOPN-CNT             PIC 9(05) VALUE ZERO.

      *    有効な顧客を顧客番号順に、6件目から20件だけ取り出す
      *    ページング用カーソル（OFFSET...FETCH NEXT...ROWS ONLY）。
           EXEC SQL DECLARE CSR-PAGE CURSOR FOR
               SELECT KOKYAKU_NO, KOKYAKU_MEI
                 FROM FLDB.KOKYAKU
                WHERE SAKUJO_FLG = '0'
                ORDER BY KOKYAKU_NO
                OFFSET 5 ROWS
                FETCH NEXT 20 ROWS ONLY
               FOR FETCH ONLY
           END-EXEC.

      *    販売中の商品を単価の高い順に10件だけ、確定読取り隔離
      *    レベルで取り出すカーソル（FETCH FIRST・OPTIMIZE FOR・
      *    WITH URの併用）。
           EXEC SQL DECLARE CSR-TOPN CURSOR FOR
               SELECT SHOHIN_CD, SHOHIN_MEI, TANKA
                 FROM FLDB.SHOHIN
                WHERE HANBAI_KBN = '1'
                ORDER BY TANKA DESC
                FETCH FIRST 10 ROWS ONLY
               FOR FETCH ONLY
               WITH UR
               OPTIMIZE FOR 10 ROWS
           END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-NEXT-VALUE
           PERFORM 1100-PREVIOUS-VALUE
           PERFORM 2000-SPECIAL-REGISTERS
           PERFORM 3000-ROW-CHANGE-TIMESTAMP
           PERFORM 3100-BUSINESS-TIME
           PERFORM 4000-PAGE
           PERFORM 5000-JSON-VAL
           PERFORM 6000-TOPN
           DISPLAY 'CSQ408 PAGE=' WS-PAGE-CNT
                   ' TOPN=' WS-TOPN-CNT
           STOP RUN.

      *    採番シーケンスから次の値を取得する（NEXT VALUE FOR）。
       1000-NEXT-VALUE.
           EXEC SQL
               SELECT NEXT VALUE FOR SEQ1
                 INTO :WS-NEXT-SEQ
                 FROM SYSIBM.SYSDUMMY1
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'NEXT VALUE FORの実行に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           DISPLAY 'CSQ408 採番値 = ' WS-NEXT-SEQ.

      *    直前にNEXT VALUE FORで払い出した値を読み直す
      *    （PREVIOUS VALUE FOR）。
       1100-PREVIOUS-VALUE.
           EXEC SQL
               SELECT PREVIOUS VALUE FOR SEQ1
                 INTO :WS-PREV-SEQ
                 FROM SYSIBM.SYSDUMMY1
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'PREVIOUS VALUE FORの実行に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           DISPLAY 'CSQ408 直前の採番値 = ' WS-PREV-SEQ.

      *    現在日付・現在時刻・現在タイムスタンプ・タイムゾーン・
      *    接続サーバー名・現在SQLIDを一度に取得する。
       2000-SPECIAL-REGISTERS.
           EXEC SQL
               SELECT CURRENT DATE, CURRENT TIME, CURRENT TIMESTAMP,
                      CURRENT TIMEZONE, CURRENT SERVER, CURRENT SQLID
                 INTO :WS-C-DATE, :WS-C-TIME, :WS-C-TS,
                      :WS-C-TZ, :WS-C-SERVER, :WS-C-SQLID
                 FROM SYSIBM.SYSDUMMY1
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '特殊レジスタの取得に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           DISPLAY 'CSQ408 現在SQLID = ' WS-C-SQLID.

      *    指定顧客の行変更タイムスタンプを、指定時点の状態を示す
      *    FOR SYSTEM_TIME AS OFで取得する。CSD401のDECLARE TABLE
      *    に行変更列と期間列がないため、構文のみを確認する。
       3000-ROW-CHANGE-TIMESTAMP.
           EXEC SQL
               SELECT ROW CHANGE TIMESTAMP FOR K
                 INTO :WS-ROW-CHANGE-TS
                 FROM FLDB.KOKYAKU
                      FOR SYSTEM_TIME AS OF :WS-SYSTEM-TS AS K
                WHERE K.KOKYAKU_NO = :WS-KOKYAKU-NO-IN
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   DISPLAY 'CSQ408 行変更TS = ' WS-ROW-CHANGE-TS
               WHEN +100
                   DISPLAY 'CSQ408 該当する顧客がありません'
               WHEN OTHER
                   MOVE 'ROW CHANGE TIMESTAMPの取得に失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

      *    指定した業務期間時点の顧客名をFOR BUSINESS_TIME AS OFで
      *    取得する。CSD401に業務期間列がないため、構文のみを確認
      *    する。
       3100-BUSINESS-TIME.
           EXEC SQL
               SELECT KOKYAKU_MEI
                 INTO :WS-KOKYAKU-MEI-BT
                 FROM FLDB.KOKYAKU
                      FOR BUSINESS_TIME AS OF :WS-BUSINESS-TS AS B
                WHERE B.KOKYAKU_NO = :WS-KOKYAKU-NO-IN
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   DISPLAY 'CSQ408 業務期間時点の顧客名 = '
                           WS-KOKYAKU-MEI-BT
               WHEN +100
                   DISPLAY 'CSQ408 該当する顧客がありません'
               WHEN OTHER
                   MOVE 'FOR BUSINESS_TIME AS OFの取得に失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       4000-PAGE.
           EXEC SQL OPEN CSR-PAGE END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'ページングカーソルの開始に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 4100-PAGE-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-PAGE END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'ページングカーソルの終了に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       4100-PAGE-FETCH.
           EXEC SQL
               FETCH CSR-PAGE
                INTO :WS-KOKYAKU-NO-PAGE, :WS-KOKYAKU-MEI-PAGE
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-PAGE-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE 'ページングカーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

      *    ホスト変数に保持したJSONテキストから項目aの下のbの値を
      *    取り出す（JSON_VAL）。SYSTOOLSスキーマのJSON_VALと
      *    JSON2BSONを使う。Db2のJSONサポートが導入済みと見なす。
       5000-JSON-VAL.
           EXEC SQL
               SELECT SYSTOOLS.JSON_VAL(
                          SYSTOOLS.JSON2BSON(:WS-JSON-TEXT),
                          'a.b', 's:32')
                 INTO :WS-JSON-RESULT
                 FROM SYSIBM.SYSDUMMY1
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'JSON_VALの実行に失敗しました' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           DISPLAY 'CSQ408 JSON_VAL結果 = ' WS-JSON-RESULT.

       6000-TOPN.
           EXEC SQL OPEN CSR-TOPN END-EXEC
           IF SQLCODE NOT = 0
               MOVE '上位N件カーソルの開始に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           PERFORM 6100-TOPN-FETCH UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-TOPN END-EXEC
           IF SQLCODE NOT = 0
               MOVE '上位N件カーソルの終了に失敗しました'
                    TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       6100-TOPN-FETCH.
           EXEC SQL
               FETCH CSR-TOPN
                INTO :WS-SHOHIN-CD-TOPN, :WS-SHOHIN-MEI-TOPN,
                     :WS-TANKA-TOPN
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-TOPN-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   MOVE '上位N件カーソルのFETCHに失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       9900-SQL-ERROR.
           DISPLAY 'CSQ408 SQL ERROR SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-ERR-MSG
           MOVE 12 TO RETURN-CODE
           GOBACK.

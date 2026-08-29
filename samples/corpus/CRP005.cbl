      *----------------------------------------------------------*
      *  PROGRAM-ID : CRP005                                     *
      *  機能       : 在庫数更新・抽出バッチ（Db2、日次）        *
      *  処理概要   : 在庫トランザクション(CRPIN2)を読み込み、    *
      *               Db2の在庫マスタ表(CRPDB.ZAIKOM)を更新する。  *
      *               更新後の内容をカーソルで抽出しCRPEXTRへ     *
      *               出力する。EXEC SQLの直後は必ずSQLCODEを     *
      *               検査し、WHENEVERには頼らない。              *
      *  用途       : 良好実装コーパス（誤検出計測用、defect無し）*
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CRP005.
       AUTHOR.      CRP-CORPUS-DEV.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT CRPIN2    ASSIGN TO CRPIN2
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-CRPIN2-STATUS.
           SELECT CRPEXTR   ASSIGN TO CRPEXTR
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-CRPEXTR-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD  CRPIN2
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
       01  TRX-レコード.
           05  TRX-商品コード             PIC X(08).
           05  TRX-倉庫コード             PIC X(04).
           05  TRX-増減数量               PIC S9(07)    COMP-3.
           05  TRX-更新区分               PIC X(01).
               88  TRX-増加                   VALUE '1'.
               88  TRX-減少                   VALUE '2'.

       FD  CRPEXTR
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
       01  EXTR-レコード.
           05  EXTR-商品コード            PIC X(08).
           05  EXTR-倉庫コード            PIC X(04).
           05  EXTR-在庫数量              PIC S9(07)    COMP-3.
           05  EXTR-引当数量              PIC S9(07)    COMP-3.
           05  EXTR-処理日                PIC 9(08).

       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  HOST-商品コード                PIC X(08).
       01  HOST-倉庫コード                PIC X(04).
       01  HOST-在庫数量                  PIC S9(07)    COMP-3.
       01  HOST-引当数量                  PIC S9(07)    COMP-3.
       01  HOST-増減数量                  PIC S9(07)    COMP-3.
           EXEC SQL END DECLARE SECTION END-EXEC.

       01  WS-ファイル状態.
           05  WS-CRPIN2-STATUS           PIC X(02).
           05  WS-CRPEXTR-STATUS          PIC X(02).

       01  WS-制御フラグ.
           05  WS-EOF-FLAG                PIC X(01) VALUE 'N'.
               88  WS-EOF                     VALUE 'Y'.
           05  WS-IOエラー                PIC X(01) VALUE 'N'.
               88  WS-IOエラーあり            VALUE 'Y'.

       01  WS-作業項目.
           05  WS-処理件数                PIC 9(05) VALUE ZERO.
           05  WS-エラー件数              PIC 9(05) VALUE ZERO.

       PROCEDURE DIVISION.
       0000-メイン処理.
           PERFORM 1000-初期化処理
           PERFORM 2000-在庫更新処理 UNTIL WS-EOF
           PERFORM 3000-抽出ファイル出力
           PERFORM 8000-終了処理
           STOP RUN.

       1000-初期化処理.
           OPEN INPUT  CRPIN2
           IF WS-CRPIN2-STATUS NOT = '00'
               DISPLAY 'CRP005 CRPIN2オープン異常 STATUS='
                       WS-CRPIN2-STATUS
               MOVE 'Y' TO WS-IOエラー
           END-IF
           OPEN OUTPUT CRPEXTR
           IF WS-CRPEXTR-STATUS NOT = '00'
               DISPLAY 'CRP005 CRPEXTRオープン異常 STATUS='
                       WS-CRPEXTR-STATUS
               MOVE 'Y' TO WS-IOエラー
           END-IF
           IF WS-IOエラーあり
               MOVE 'Y' TO WS-EOF-FLAG
           ELSE
               PERFORM 1100-トランザクション読込
           END-IF.

       1100-トランザクション読込.
           READ CRPIN2
               AT END
                   MOVE 'Y' TO WS-EOF-FLAG
           END-READ
           IF NOT WS-EOF AND WS-CRPIN2-STATUS NOT = '00'
               DISPLAY 'CRP005 CRPIN2読込異常 STATUS='
                       WS-CRPIN2-STATUS
               MOVE 'Y' TO WS-EOF-FLAG
           END-IF.

       2000-在庫更新処理.
           ADD 1 TO WS-処理件数
           MOVE TRX-商品コード TO HOST-商品コード
           MOVE TRX-倉庫コード TO HOST-倉庫コード
           EXEC SQL
               SELECT ZAIKO_SU, HIKIATE_SU
                 INTO :HOST-在庫数量, :HOST-引当数量
                 FROM CRPDB.ZAIKOM
                WHERE SHOHIN_CD = :HOST-商品コード
                  AND SOKO_CD   = :HOST-倉庫コード
                FETCH FIRST 1 ROW ONLY
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   PERFORM 2100-在庫数更新
               WHEN 100
                   PERFORM 2200-在庫マスタ新規登録
               WHEN OTHER
                   DISPLAY 'CRP005 在庫照会エラー SQLCODE='
                           SQLCODE ' 商品コード=' TRX-商品コード
                   ADD 1 TO WS-エラー件数
           END-EVALUATE
           PERFORM 1100-トランザクション読込.

       2100-在庫数更新.
           IF TRX-増加
               MOVE TRX-増減数量 TO HOST-増減数量
           ELSE
               COMPUTE HOST-増減数量 = 0 - TRX-増減数量
           END-IF
           EXEC SQL
               UPDATE CRPDB.ZAIKOM
                  SET ZAIKO_SU = ZAIKO_SU + :HOST-増減数量
                WHERE SHOHIN_CD = :HOST-商品コード
                  AND SOKO_CD   = :HOST-倉庫コード
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CRP005 在庫数更新エラー SQLCODE='
                       SQLCODE ' 商品コード=' TRX-商品コード
               ADD 1 TO WS-エラー件数
           END-IF.

       2200-在庫マスタ新規登録.
           MOVE TRX-増減数量 TO HOST-増減数量
           EXEC SQL
               INSERT INTO CRPDB.ZAIKOM
                      (SHOHIN_CD, SOKO_CD, ZAIKO_SU, HIKIATE_SU)
               VALUES (:HOST-商品コード, :HOST-倉庫コード,
                       :HOST-増減数量, 0)
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CRP005 新規登録エラー SQLCODE='
                       SQLCODE ' 商品コード=' TRX-商品コード
               ADD 1 TO WS-エラー件数
           END-IF.

       3000-抽出ファイル出力.
           EXEC SQL
               DECLARE CRPZAIKOCUR CURSOR FOR
                   SELECT SHOHIN_CD, SOKO_CD, ZAIKO_SU, HIKIATE_SU
                     FROM CRPDB.ZAIKOM
                    FOR FETCH ONLY
           END-EXEC
           EXEC SQL
               OPEN CRPZAIKOCUR
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CRP005 カーソルOPENエラー SQLCODE=' SQLCODE
           ELSE
               PERFORM 3100-抽出データフェッチ
                   UNTIL SQLCODE NOT = 0
               EXEC SQL
                   CLOSE CRPZAIKOCUR
               END-EXEC
               IF SQLCODE NOT = 0
                   DISPLAY 'CRP005 カーソルCLOSEエラー SQLCODE='
                           SQLCODE
               END-IF
           END-IF.

       3100-抽出データフェッチ.
           EXEC SQL
               FETCH CRPZAIKOCUR
                   INTO :HOST-商品コード, :HOST-倉庫コード,
                        :HOST-在庫数量, :HOST-引当数量
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   MOVE HOST-商品コード TO EXTR-商品コード
                   MOVE HOST-倉庫コード TO EXTR-倉庫コード
                   MOVE HOST-在庫数量   TO EXTR-在庫数量
                   MOVE HOST-引当数量   TO EXTR-引当数量
                   MOVE FUNCTION CURRENT-DATE(1:8) TO EXTR-処理日
                   WRITE EXTR-レコード
                   IF WS-CRPEXTR-STATUS NOT = '00'
                       DISPLAY 'CRP005 CRPEXTR書込異常 STATUS='
                               WS-CRPEXTR-STATUS
                   END-IF
               WHEN 100
                   CONTINUE
               WHEN OTHER
                   DISPLAY 'CRP005 カーソルFETCHエラー SQLCODE='
                           SQLCODE
           END-EVALUATE.

       8000-終了処理.
           CLOSE CRPIN2
           IF WS-CRPIN2-STATUS NOT = '00'
               DISPLAY 'CRP005 CRPIN2クローズ異常 STATUS='
                       WS-CRPIN2-STATUS
           END-IF
           CLOSE CRPEXTR
           IF WS-CRPEXTR-STATUS NOT = '00'
               DISPLAY 'CRP005 CRPEXTRクローズ異常 STATUS='
                       WS-CRPEXTR-STATUS
           END-IF
           DISPLAY 'CRP005 処理件数   = ' WS-処理件数
           DISPLAY 'CRP005 エラー件数 = ' WS-エラー件数.

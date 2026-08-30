      *----------------------------------------------------------*
      *  PROGRAM-ID : CRP002                                     *
      *  機能       : 商品マスタ更新バッチ（日次）                *
      *  処理概要   : 商品トランザクション(CRPTRAN)を読み込み、    *
      *               VSAM KSDS商品マスタ(CRPMSTR)へ新規登録・     *
      *               更新・削除のいずれかを行う。                *
      *  用途       : 良好実装コーパス（誤検出計測用、defect無し）*
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CRP002.
       AUTHOR.      CRP-CORPUS-DEV.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT CRPTRAN   ASSIGN TO CRPTRAN
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-CRPTRAN-STATUS.
           SELECT CRPMSTR   ASSIGN TO CRPMSTR
                  ORGANIZATION IS INDEXED
                  ACCESS MODE IS DYNAMIC
                  RECORD KEY IS CRP2-商品コード
                  FILE STATUS IS WS-MASTER-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD  CRPTRAN
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
       01  TRAN-REC.
           05  TRAN-処理区分              PIC X(01).
               88  TRAN-新規登録              VALUE '1'.
               88  TRAN-更新                  VALUE '2'.
               88  TRAN-削除                  VALUE '3'.
           05  TRAN-商品コード            PIC X(08).
           05  TRAN-商品名                PIC X(20).
           05  TRAN-在庫数量              PIC S9(07)    COMP-3.
           05  TRAN-在庫上限数量          PIC S9(07)    COMP-3.

       FD  CRPMSTR
           LABEL RECORDS ARE STANDARD.
           COPY CRPCPY2.

       WORKING-STORAGE SECTION.
       01  WS-ファイル状態.
           05  WS-CRPTRAN-STATUS          PIC X(02).
           05  WS-MASTER-STATUS           PIC X(02).

       01  WS-制御フラグ.
           05  WS-EOF-FLAG                PIC X(01) VALUE 'N'.
               88  WS-EOF                     VALUE 'Y'.
           05  WS-IOエラー                PIC X(01) VALUE 'N'.
               88  WS-IOエラーあり            VALUE 'Y'.

       01  WS-件数集計.
           05  WS-新規件数                PIC 9(05) VALUE ZERO.
           05  WS-更新件数                PIC 9(05) VALUE ZERO.
           05  WS-削除件数                PIC 9(05) VALUE ZERO.
           05  WS-エラー件数              PIC 9(05) VALUE ZERO.

       PROCEDURE DIVISION.
       0000-メイン処理.
           PERFORM 1000-初期化処理
           PERFORM 2000-トランザクション読込
           PERFORM 3000-更新メイン UNTIL WS-EOF
           PERFORM 8000-終了処理
           STOP RUN.

       1000-初期化処理.
           OPEN INPUT CRPTRAN
           IF WS-CRPTRAN-STATUS NOT = '00'
               DISPLAY 'CRP002 CRPTRANオープン異常 STATUS='
                       WS-CRPTRAN-STATUS
               MOVE 'Y' TO WS-IOエラー
           END-IF
           OPEN I-O   CRPMSTR
           IF WS-MASTER-STATUS NOT = '00'
               DISPLAY 'CRP002 CRPMSTRオープン異常 STATUS='
                       WS-MASTER-STATUS
               MOVE 'Y' TO WS-IOエラー
           END-IF.

       2000-トランザクション読込.
           IF WS-IOエラーあり
               MOVE 'Y' TO WS-EOF-FLAG
           ELSE
               READ CRPTRAN
                   AT END
                       MOVE 'Y' TO WS-EOF-FLAG
               END-READ
               IF NOT WS-EOF AND WS-CRPTRAN-STATUS NOT = '00'
                   DISPLAY 'CRP002 CRPTRAN読込異常 STATUS='
                           WS-CRPTRAN-STATUS
                   MOVE 'Y' TO WS-EOF-FLAG
               END-IF
           END-IF.

       3000-更新メイン.
           EVALUATE TRUE
               WHEN TRAN-新規登録
                   PERFORM 3100-新規登録処理
               WHEN TRAN-更新
                   PERFORM 3200-更新処理
               WHEN TRAN-削除
                   PERFORM 3300-削除処理
               WHEN OTHER
                   DISPLAY 'CRP002 処理区分コード不正 商品コード='
                           TRAN-商品コード
                   ADD 1 TO WS-エラー件数
           END-EVALUATE
           PERFORM 2000-トランザクション読込.

       3100-新規登録処理.
           MOVE TRAN-商品コード      TO CRP2-商品コード
           MOVE TRAN-商品名          TO CRP2-商品名
           MOVE TRAN-在庫数量        TO CRP2-在庫数量
           MOVE TRAN-在庫上限数量    TO CRP2-在庫上限数量
           IF CRP2-在庫数量 > ZERO
               SET CRP2-在庫あり TO TRUE
           ELSE
               SET CRP2-在庫なし TO TRUE
           END-IF
           MOVE FUNCTION CURRENT-DATE(1:14) TO CRP2-更新日時
           WRITE CRP2-商品マスタレコード
               INVALID KEY
                   DISPLAY 'CRP002 新規登録エラー(重複キー) '
                           '商品コード=' TRAN-商品コード
                   ADD 1 TO WS-エラー件数
               NOT INVALID KEY
                   ADD 1 TO WS-新規件数
           END-WRITE.

       3200-更新処理.
           MOVE TRAN-商品コード TO CRP2-商品コード
           READ CRPMSTR
               INVALID KEY
                   DISPLAY 'CRP002 更新対象なし 商品コード='
                           TRAN-商品コード
                   ADD 1 TO WS-エラー件数
               NOT INVALID KEY
                   PERFORM 3210-マスタ更新
           END-READ.

       3210-マスタ更新.
           MOVE TRAN-商品名          TO CRP2-商品名
           MOVE TRAN-在庫数量        TO CRP2-在庫数量
           MOVE TRAN-在庫上限数量    TO CRP2-在庫上限数量
           IF CRP2-在庫数量 > ZERO
               SET CRP2-在庫あり TO TRUE
           ELSE
               SET CRP2-在庫なし TO TRUE
           END-IF
           MOVE FUNCTION CURRENT-DATE(1:14) TO CRP2-更新日時
           REWRITE CRP2-商品マスタレコード
               INVALID KEY
                   DISPLAY 'CRP002 更新エラー 商品コード='
                           TRAN-商品コード
                   ADD 1 TO WS-エラー件数
               NOT INVALID KEY
                   ADD 1 TO WS-更新件数
           END-REWRITE.

       3300-削除処理.
           MOVE TRAN-商品コード TO CRP2-商品コード
           DELETE CRPMSTR
               INVALID KEY
                   DISPLAY 'CRP002 削除対象なし 商品コード='
                           TRAN-商品コード
                   ADD 1 TO WS-エラー件数
               NOT INVALID KEY
                   ADD 1 TO WS-削除件数
           END-DELETE.

       8000-終了処理.
           CLOSE CRPTRAN
           IF WS-CRPTRAN-STATUS NOT = '00'
               DISPLAY 'CRP002 CRPTRANクローズ異常 STATUS='
                       WS-CRPTRAN-STATUS
           END-IF
           CLOSE CRPMSTR
           IF WS-MASTER-STATUS NOT = '00'
               DISPLAY 'CRP002 CRPMSTRクローズ異常 STATUS='
                       WS-MASTER-STATUS
           END-IF
           DISPLAY 'CRP002 新規件数   = ' WS-新規件数
           DISPLAY 'CRP002 更新件数   = ' WS-更新件数
           DISPLAY 'CRP002 削除件数   = ' WS-削除件数
           DISPLAY 'CRP002 エラー件数 = ' WS-エラー件数.

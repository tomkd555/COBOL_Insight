      *----------------------------------------------------------*
      *  PROGRAM-ID : CRP001                                     *
      *  機能       : 入庫データ検証バッチ（日次）                *
      *  処理概要   : 入庫日次ファイル(CRPIN)を読み込み、明細の    *
      *               上限件数と金額合計を検証し、正常分を        *
      *               CRPVALIDへ、異常分をCRPERRへ出力する。      *
      *  用途       : 良好実装コーパス（誤検出計測用、defect無し）*
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CRP001.
       AUTHOR.      CRP-CORPUS-DEV.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT CRPIN     ASSIGN TO CRPIN
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-CRPIN-STATUS.
           SELECT CRPVALID  ASSIGN TO CRPVALID
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-CRPVALID-STATUS.
           SELECT CRPERR    ASSIGN TO CRPERR
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-CRPERR-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD  CRPIN
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
           COPY CRPCPY1.

       FD  CRPVALID
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
       01  VALID-REC                       PIC X(253).

       FD  CRPERR
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
       01  ERROR-REC.
           05  ERR-伝票番号               PIC X(10).
           05  ERR-エラー内容             PIC X(40).
           05  FILLER                     PIC X(150).

       WORKING-STORAGE SECTION.
       01  WS-ファイル状態.
           05  WS-CRPIN-STATUS            PIC X(02).
           05  WS-CRPVALID-STATUS         PIC X(02).
           05  WS-CRPERR-STATUS           PIC X(02).

       01  WS-制御フラグ.
           05  WS-EOF-FLAG                PIC X(01) VALUE 'N'.
               88  WS-EOF                     VALUE 'Y'.
           05  WS-IOエラー                PIC X(01) VALUE 'N'.
               88  WS-IOエラーあり            VALUE 'Y'.
           05  WS-処理区分エラー          PIC X(01) VALUE 'N'.
               88  WS-処理区分エラーあり      VALUE 'Y'.

       01  WS-作業項目.
           05  WS-IDX                     PIC S9(04)    COMP
                                            VALUE ZERO.
           05  WS-明細上限                PIC S9(03)    COMP-3
                                            VALUE 10.
           05  WS-検証件数                PIC S9(03)    COMP-3
                                            VALUE ZERO.
           05  WS-検証金額                PIC S9(09)V99 COMP-3
                                            VALUE ZERO.
           05  WS-上限金額                PIC S9(09)V99 COMP-3
                                            VALUE 5000000.00.
           05  WS-合計チェック            PIC S9(09)V99 COMP-3
                                            VALUE ZERO.
           05  WS-確認金額                PIC S9(11)V99 COMP-3
                                            VALUE ZERO.
           05  WS-エラー件数              PIC 9(05) VALUE ZERO.
           05  WS-処理件数                PIC 9(05) VALUE ZERO.

       01  WS-エラーメッセージ            PIC X(40) VALUE SPACES.

       PROCEDURE DIVISION.
       0000-メイン処理.
           PERFORM 1000-初期化処理
           PERFORM 2000-入庫データ処理 UNTIL WS-EOF
           PERFORM 8000-終了処理
           STOP RUN.

       1000-初期化処理.
           OPEN INPUT  CRPIN
           IF WS-CRPIN-STATUS NOT = '00'
               DISPLAY 'CRP001 CRPINオープン異常 STATUS='
                       WS-CRPIN-STATUS
               MOVE 'Y' TO WS-IOエラー
           END-IF
           OPEN OUTPUT CRPVALID
           IF WS-CRPVALID-STATUS NOT = '00'
               DISPLAY 'CRP001 CRPVALIDオープン異常 STATUS='
                       WS-CRPVALID-STATUS
               MOVE 'Y' TO WS-IOエラー
           END-IF
           OPEN OUTPUT CRPERR
           IF WS-CRPERR-STATUS NOT = '00'
               DISPLAY 'CRP001 CRPERRオープン異常 STATUS='
                       WS-CRPERR-STATUS
               MOVE 'Y' TO WS-IOエラー
           END-IF
           IF WS-IOエラーあり
               MOVE 'Y' TO WS-EOF-FLAG
           ELSE
               PERFORM 1100-入庫データ読込
           END-IF.

       1100-入庫データ読込.
           READ CRPIN INTO CRP1-入庫レコード
               AT END
                   MOVE 'Y' TO WS-EOF-FLAG
           END-READ
           IF NOT WS-EOF AND WS-CRPIN-STATUS NOT = '00'
               DISPLAY 'CRP001 CRPIN読込異常 STATUS='
                       WS-CRPIN-STATUS
               MOVE 'Y' TO WS-EOF-FLAG
           END-IF.

       2000-入庫データ処理.
           ADD 1 TO WS-処理件数
           PERFORM 2100-明細検証
           PERFORM 2200-結果判定
           PERFORM 1100-入庫データ読込.

       2100-明細検証.
           MOVE ZERO TO WS-検証金額
           MOVE ZERO TO WS-合計チェック
           MOVE 'N'  TO WS-処理区分エラー
           EVALUATE TRUE
               WHEN CRP1-新規登録
                   PERFORM 2110-新規登録検証
               WHEN CRP1-訂正
                   PERFORM 2120-訂正検証
               WHEN CRP1-取消
                   MOVE ZERO TO WS-検証金額
               WHEN OTHER
                   MOVE 'Y' TO WS-処理区分エラー
                   MOVE '処理区分コード不正'  TO WS-エラーメッセージ
           END-EVALUATE.

       2110-新規登録検証.
           MOVE CRP1-入庫金額合計 TO WS-検証金額
           PERFORM 3000-明細合計計算 THRU 3000-明細合計計算-EXIT.

       2120-訂正検証.
           MOVE CRP1-入庫金額合計 TO WS-検証金額
           PERFORM 3000-明細合計計算 THRU 3000-明細合計計算-EXIT.

       3000-明細合計計算.
           IF CRP1-明細件数 > WS-明細上限
               DISPLAY 'CRP001 明細件数が上限を超過 件数='
                       CRP1-明細件数
               MOVE WS-明細上限 TO WS-検証件数
           ELSE
               MOVE CRP1-明細件数 TO WS-検証件数
           END-IF
           PERFORM VARYING WS-IDX FROM 1 BY 1
                   UNTIL WS-IDX > WS-検証件数
               ADD CRP1-金額(WS-IDX) TO WS-合計チェック
                   ON SIZE ERROR
                       DISPLAY 'CRP001 明細合計計算でSIZE ERROR'
               END-ADD
           END-PERFORM
           IF WS-合計チェック NOT = CRP1-入庫金額合計
               DISPLAY 'CRP001 明細金額合計が不一致 伝票番号='
                       CRP1-伝票番号
           END-IF.

       3000-明細合計計算-EXIT.
           EXIT.

       2200-結果判定.
           IF WS-処理区分エラーあり OR WS-検証金額 > WS-上限金額
               ADD 1 TO WS-エラー件数
               MOVE CRP1-伝票番号           TO ERR-伝票番号
               IF WS-検証金額 > WS-上限金額
                   MOVE '金額超過エラー'    TO WS-エラーメッセージ
               END-IF
               MOVE WS-エラーメッセージ     TO ERR-エラー内容
               WRITE ERROR-REC
               IF WS-CRPERR-STATUS NOT = '00'
                   DISPLAY 'CRP001 CRPERR書込異常 STATUS='
                           WS-CRPERR-STATUS
               END-IF
           ELSE
               MOVE CRP1-入庫金額合計 TO WS-確認金額
               DISPLAY 'CRP001 検証済み入庫金額 = ' WS-確認金額
               MOVE CRP1-入庫レコード TO VALID-REC
               WRITE VALID-REC
               IF WS-CRPVALID-STATUS NOT = '00'
                   DISPLAY 'CRP001 CRPVALID書込異常 STATUS='
                           WS-CRPVALID-STATUS
               END-IF
           END-IF.

       8000-終了処理.
           CLOSE CRPIN
           IF WS-CRPIN-STATUS NOT = '00'
               DISPLAY 'CRP001 CRPINクローズ異常 STATUS='
                       WS-CRPIN-STATUS
           END-IF
           CLOSE CRPVALID
           IF WS-CRPVALID-STATUS NOT = '00'
               DISPLAY 'CRP001 CRPVALIDクローズ異常 STATUS='
                       WS-CRPVALID-STATUS
           END-IF
           CLOSE CRPERR
           IF WS-CRPERR-STATUS NOT = '00'
               DISPLAY 'CRP001 CRPERRクローズ異常 STATUS='
                       WS-CRPERR-STATUS
           END-IF
           DISPLAY 'CRP001 処理件数   = ' WS-処理件数
           DISPLAY 'CRP001 エラー件数 = ' WS-エラー件数.

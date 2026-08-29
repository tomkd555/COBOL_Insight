      *----------------------------------------------------------*
      *  PROGRAM-ID : CRP008                                     *
      *  機能       : 商品区分コード検証バッチ                   *
      *  処理概要   : CRPCODEを読み込み、区分コードの妥当性を     *
      *               検証して件数を集計する。PERFORM単一段落     *
      *               (THRUなし)を基本のスタイルとするが、        *
      *               2000-検証SECTION内の早期終了だけは同一      *
      *               SECTION内のEXIT段落へのGO TOで実現する      *
      *               （PERFORM...THRUの範囲内に収まるため安全）。*
      *  用途       : 良好実装コーパス（スタイル系ルールの        *
      *               誤検出計測用、defect無し）                  *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CRP008.
       AUTHOR.      CRP-CORPUS-DEV.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT CRPCODE   ASSIGN TO CRPCODE
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-CRPCODE-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD  CRPCODE
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
       01  CODE-REC.
           05  CODE-商品コード            PIC X(08).
           05  CODE-区分                  PIC X(01).
               88  CODE-有効                  VALUE '1'.
               88  CODE-無効                  VALUE '9'.

       WORKING-STORAGE SECTION.
       01  WS-ファイル状態.
           05  WS-CRPCODE-STATUS          PIC X(02).

       01  WS-制御フラグ.
           05  WS-EOF-FLAG                PIC X(01) VALUE 'N'.
               88  WS-EOF                     VALUE 'Y'.

       01  WS-件数集計.
           05  WS-処理件数                PIC 9(05) VALUE ZERO.
           05  WS-有効件数                PIC 9(05) VALUE ZERO.
           05  WS-無効件数                PIC 9(05) VALUE ZERO.
           05  WS-不正件数                PIC 9(05) VALUE ZERO.

       PROCEDURE DIVISION.
       0000-制御SECTION SECTION.
       0000-メイン処理.
           PERFORM 1000-初期化処理
           PERFORM 2000-検証メイン UNTIL WS-EOF
           PERFORM 8000-終了処理
           STOP RUN.

       1000-初期化SECTION SECTION.
       1000-初期化処理.
           OPEN INPUT CRPCODE
           IF WS-CRPCODE-STATUS NOT = '00'
               DISPLAY 'CRP008 CRPCODEオープン異常 STATUS='
                       WS-CRPCODE-STATUS
               MOVE 'Y' TO WS-EOF-FLAG
           ELSE
               PERFORM 1100-データ読込
           END-IF.

       1100-データ読込.
           READ CRPCODE
               AT END
                   MOVE 'Y' TO WS-EOF-FLAG
           END-READ
           IF NOT WS-EOF AND WS-CRPCODE-STATUS NOT = '00'
               DISPLAY 'CRP008 CRPCODE読込異常 STATUS='
                       WS-CRPCODE-STATUS
               MOVE 'Y' TO WS-EOF-FLAG
           END-IF.

       2000-検証SECTION SECTION.
       2000-検証メイン.
           PERFORM 2000-検証処理 THRU 2000-検証処理-EXIT
           PERFORM 1100-データ読込.

       2000-検証処理.
           ADD 1 TO WS-処理件数
           IF CODE-有効
               PERFORM 2100-有効処理
           ELSE
               IF CODE-無効
                   PERFORM 2200-無効処理
               ELSE
                   ADD 1 TO WS-不正件数
                   DISPLAY 'CRP008 区分コード不正 商品コード='
                           CODE-商品コード
                   GO TO 2000-検証処理-EXIT
               END-IF
           END-IF.

       2100-有効処理.
           ADD 1 TO WS-有効件数.

       2200-無効処理.
           ADD 1 TO WS-無効件数.

       2000-検証処理-EXIT.
           EXIT.

       8000-終了SECTION SECTION.
       8000-終了処理.
           CLOSE CRPCODE
           IF WS-CRPCODE-STATUS NOT = '00'
               DISPLAY 'CRP008 CRPCODEクローズ異常 STATUS='
                       WS-CRPCODE-STATUS
           END-IF
           DISPLAY 'CRP008 処理件数   = ' WS-処理件数
           DISPLAY 'CRP008 有効件数   = ' WS-有効件数
           DISPLAY 'CRP008 無効件数   = ' WS-無効件数
           DISPLAY 'CRP008 不正件数   = ' WS-不正件数.

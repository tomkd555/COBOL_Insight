      *----------------------------------------------------------*
      *  PROGRAM-ID : CRP009                                     *
      *  機能       : 口座残高差異算出バッチ                     *
      *  処理概要   : 認証情報ファイル(CRPAUTH)からIDと           *
      *               パスワードを読み込み、パスワードはマスク    *
      *               した値だけを表示する。残高ファイル(CRPBAL) *
      *               を読み込み、前月残高との差異(マイナスも     *
      *               ありうる)を算出し、STRING/UNSTRINGで        *
      *               明細を編集する。                            *
      *  用途       : 良好実装コーパス（誤検出計測用、defect無し）*
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CRP009.
       AUTHOR.      CRP-CORPUS-DEV.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT CRPAUTH   ASSIGN TO CRPAUTH
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-CRPAUTH-STATUS.
           SELECT CRPBAL    ASSIGN TO CRPBAL
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-CRPBAL-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD  CRPAUTH
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
       01  AUTH-REC.
           05  AUTH-利用者ID              PIC X(08).
           05  AUTH-パスワード            PIC X(16).

       FD  CRPBAL
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
       01  BAL-REC.
           05  BAL-口座番号               PIC X(10).
           05  BAL-前月残高               PIC S9(09)V99 COMP-3.
           05  BAL-当月残高               PIC S9(09)V99 COMP-3.
           05  BAL-明細CSV                PIC X(60).

       WORKING-STORAGE SECTION.
       01  WS-ファイル状態.
           05  WS-CRPAUTH-STATUS          PIC X(02).
           05  WS-CRPBAL-STATUS           PIC X(02).

       01  WS-制御フラグ.
           05  WS-EOF-FLAG                PIC X(01) VALUE 'N'.
               88  WS-EOF                     VALUE 'Y'.
           05  WS-STRINGオーバーフロー    PIC X(01) VALUE 'N'.
               88  WS-STRINGオーバーフローあり
                                               VALUE 'Y'.
           05  WS-UNSTRINGオーバーフロー  PIC X(01) VALUE 'N'.
               88  WS-UNSTRINGオーバーフローあり
                                               VALUE 'Y'.

       01  WS-作業項目.
           05  WS-差異金額                PIC S9(09)V99 COMP-3
                                            VALUE ZERO.
           05  WS-マスクパスワード        PIC X(16) VALUE SPACES.
           05  WS-出力行                  PIC X(80) VALUE SPACES.
           05  WS-項目1                   PIC X(10) VALUE SPACES.
           05  WS-項目2                   PIC X(10) VALUE SPACES.
           05  WS-項目3                   PIC X(10) VALUE SPACES.
           05  WS-処理件数                PIC 9(05) VALUE ZERO.
           05  WS-エラー件数              PIC 9(05) VALUE ZERO.

       PROCEDURE DIVISION.
       0000-メイン処理.
           PERFORM 1000-認証情報読込
           PERFORM 1200-残高ファイル初期化
           PERFORM 2000-残高データ処理 UNTIL WS-EOF
           PERFORM 8000-終了処理
           STOP RUN.

       1000-認証情報読込.
           OPEN INPUT CRPAUTH
           IF WS-CRPAUTH-STATUS NOT = '00'
               DISPLAY 'CRP009 CRPAUTHオープン異常 STATUS='
                       WS-CRPAUTH-STATUS
           ELSE
               READ CRPAUTH
                   AT END
                       DISPLAY 'CRP009 認証情報が空です'
               END-READ
               IF WS-CRPAUTH-STATUS = '00'
                   PERFORM 1100-パスワードマスク編集
               END-IF
               CLOSE CRPAUTH
               IF WS-CRPAUTH-STATUS NOT = '00'
                   DISPLAY 'CRP009 CRPAUTHクローズ異常 STATUS='
                           WS-CRPAUTH-STATUS
               END-IF
           END-IF.

       1100-パスワードマスク編集.
           MOVE SPACES TO WS-マスクパスワード
           IF AUTH-パスワード NOT = SPACES
               MOVE ALL '*' TO WS-マスクパスワード
           END-IF
           DISPLAY 'CRP009 認証利用者ID=' AUTH-利用者ID
                   ' パスワード=' WS-マスクパスワード.

       1200-残高ファイル初期化.
           OPEN INPUT CRPBAL
           IF WS-CRPBAL-STATUS NOT = '00'
               DISPLAY 'CRP009 CRPBALオープン異常 STATUS='
                       WS-CRPBAL-STATUS
               MOVE 'Y' TO WS-EOF-FLAG
           ELSE
               PERFORM 1210-残高データ読込
           END-IF.

       1210-残高データ読込.
           READ CRPBAL
               AT END
                   MOVE 'Y' TO WS-EOF-FLAG
           END-READ
           IF NOT WS-EOF AND WS-CRPBAL-STATUS NOT = '00'
               DISPLAY 'CRP009 CRPBAL読込異常 STATUS='
                       WS-CRPBAL-STATUS
               MOVE 'Y' TO WS-EOF-FLAG
           END-IF.

       2000-残高データ処理.
           ADD 1 TO WS-処理件数
           COMPUTE WS-差異金額 = BAL-当月残高 - BAL-前月残高
               ON SIZE ERROR
                   DISPLAY 'CRP009 差異金額計算でSIZE ERROR '
                           '口座番号=' BAL-口座番号
                   ADD 1 TO WS-エラー件数
           END-COMPUTE
           PERFORM 2100-明細分割
           PERFORM 2200-出力行編集
           PERFORM 1210-残高データ読込.

       2100-明細分割.
           MOVE 'N' TO WS-UNSTRINGオーバーフロー
           MOVE SPACES TO WS-項目1 WS-項目2 WS-項目3
           UNSTRING BAL-明細CSV DELIMITED BY ','
               INTO WS-項目1 WS-項目2 WS-項目3
               ON OVERFLOW
                   MOVE 'Y' TO WS-UNSTRINGオーバーフロー
                   DISPLAY 'CRP009 明細CSVの項目数が上限を超過 '
                           '口座番号=' BAL-口座番号
           END-UNSTRING
           IF WS-UNSTRINGオーバーフローあり
               ADD 1 TO WS-エラー件数
           END-IF
           DISPLAY 'CRP009 明細項目 1=' WS-項目1
                   ' 2=' WS-項目2 ' 3=' WS-項目3.

       2200-出力行編集.
           MOVE 'N' TO WS-STRINGオーバーフロー
           MOVE SPACES TO WS-出力行
           STRING BAL-口座番号        DELIMITED BY SIZE
                  ' 明細1='           DELIMITED BY SIZE
                  WS-項目1            DELIMITED BY SPACE
               INTO WS-出力行
               ON OVERFLOW
                   MOVE 'Y' TO WS-STRINGオーバーフロー
                   DISPLAY 'CRP009 出力行編集でSTRINGオーバーフロー '
                           '口座番号=' BAL-口座番号
           END-STRING
           IF WS-STRINGオーバーフローあり
               ADD 1 TO WS-エラー件数
           ELSE
               DISPLAY WS-出力行
               DISPLAY 'CRP009 差異金額=' WS-差異金額
           END-IF.

       8000-終了処理.
           CLOSE CRPBAL
           IF WS-CRPBAL-STATUS NOT = '00'
               DISPLAY 'CRP009 CRPBALクローズ異常 STATUS='
                       WS-CRPBAL-STATUS
           END-IF
           DISPLAY 'CRP009 処理件数   = ' WS-処理件数
           DISPLAY 'CRP009 エラー件数 = ' WS-エラー件数.

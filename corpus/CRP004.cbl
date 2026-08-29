      *----------------------------------------------------------*
      *  PROGRAM-ID : CRP004                                     *
      *  機能       : 明細金額一括計算バッチ                      *
      *  処理概要   : 明細テーブルの数量・単価を順に取り出し、     *
      *               CRP003を呼び出して金額を計算する。          *
      *               CALLのたびにRETURN-CODEを検査する。         *
      *  呼び出し先 : CRP003（静的CALL 'CRP003'）                 *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CRP004.
       AUTHOR.      CRP-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-明細テーブル.
           05  WS-明細行 OCCURS 5 TIMES.
               10  WS-数量                PIC S9(05)    COMP-3.
               10  WS-単価                PIC S9(07)V99 COMP-3.
               10  WS-金額                PIC S9(09)V99 COMP-3.

       01  WS-作業項目.
           05  WS-IDX                     PIC S9(04)    COMP
                                            VALUE ZERO.
           05  WS-件数                    PIC S9(04)    COMP
                                            VALUE 5.
           05  WS-エラー件数              PIC 9(05) VALUE ZERO.

       PROCEDURE DIVISION.
       0000-メイン処理.
           PERFORM 1000-初期化処理
           PERFORM 2000-金額計算処理
                   VARYING WS-IDX FROM 1 BY 1
                   UNTIL WS-IDX > WS-件数
           PERFORM 8000-終了処理
           STOP RUN.

       1000-初期化処理.
           INITIALIZE WS-明細テーブル
           PERFORM VARYING WS-IDX FROM 1 BY 1
                   UNTIL WS-IDX > WS-件数
               COMPUTE WS-数量(WS-IDX) = WS-IDX * 10
               COMPUTE WS-単価(WS-IDX) = WS-IDX * 100
           END-PERFORM.

       2000-金額計算処理.
           CALL 'CRP003' USING WS-数量(WS-IDX)
                                WS-単価(WS-IDX)
                                WS-金額(WS-IDX)
           IF RETURN-CODE NOT = 0
               DISPLAY 'CRP004 CRP003呼出異常 RETURN-CODE='
                       RETURN-CODE ' 明細番号=' WS-IDX
               ADD 1 TO WS-エラー件数
           ELSE
               DISPLAY 'CRP004 明細番号=' WS-IDX
                       ' 金額=' WS-金額(WS-IDX)
           END-IF.

       8000-終了処理.
           DISPLAY 'CRP004 処理件数   = ' WS-件数
           DISPLAY 'CRP004 エラー件数 = ' WS-エラー件数.

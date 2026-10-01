      *================================================================*
      *  PROGRAM-ID : SYK008                                          *
      *  機能       : 受注番号入力画面処理（CICS疑似会話）              *
      *  処理概要   : マップSYKM01(マップセットSYKMAP1)から受注番号を    *
      *               受け取り、内容を検査する。エラー時はメッセージを   *
      *               画面表示し、正常時はSYK009へ制御を移す。          *
      *  起動元     : CICSトランザクションSYK8                          *
      *               （samples/cics/トランザクション定義表.csv参照）   *
      *  呼び出し先 : SYK009（EXEC CICS XCTL PROGRAM）                  *
      *================================================================*

       IDENTIFICATION DIVISION.
       PROGRAM-ID.  SYK008.
       AUTHOR.      SYK-SYSTEM-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-受注入力マップ.
           05  WS-ORDNO-入力               PIC X(08).
           05  WS-MSG-出力                 PIC X(40).

       01  WS-作業項目.
           05  WS-検査結果                 PIC X(01).
               88  WS-検査エラー                   VALUE 'E'.

       01  WS-応答コード.
           05  WS-RESPコード               PIC S9(08)    COMP.
           05  WS-RESP2コード              PIC S9(08)    COMP.

       PROCEDURE DIVISION.
       0000-メイン処理.
           EXEC CICS
               RECEIVE MAP('SYKM01')
                   MAPSET('SYKMAP1')
                   INTO(WS-受注入力マップ)
           END-EXEC
           PERFORM 1000-受注番号検査
           IF WS-検査エラー
               PERFORM 2000-エラーメッセージ表示
           ELSE
               PERFORM 3000-次画面遷移
           END-IF.

       1000-受注番号検査.
           IF WS-ORDNO-入力 = SPACES OR WS-ORDNO-入力 NOT NUMERIC
               MOVE 'E'                    TO WS-検査結果
               MOVE '受注番号が不正です'   TO WS-MSG-出力
           ELSE
               MOVE ' '                    TO WS-検査結果
           END-IF.

       2000-エラーメッセージ表示.
           EXEC CICS
               SEND MAP('SYKM99')
                   MAPSET('SYKMAP1')
                   FROM(WS-受注入力マップ)
                   RESP(WS-RESPコード)
                   RESP2(WS-RESP2コード)
           END-EXEC
           IF WS-RESPコード NOT = 0
               DISPLAY 'SYK008 SEND MAPエラー RESP=' WS-RESPコード
           END-IF
           EXEC CICS
               RETURN TRANSID('SYK8')
                   RESP(WS-RESPコード)
                   RESP2(WS-RESP2コード)
           END-EXEC
           IF WS-RESPコード NOT = 0
               DISPLAY 'SYK008 RETURNエラー RESP=' WS-RESPコード
           END-IF.

       3000-次画面遷移.
           EXEC CICS
               XCTL PROGRAM('SYK009')
                   RESP(WS-RESPコード)
                   RESP2(WS-RESP2コード)
           END-EXEC
           IF WS-RESPコード NOT = 0
               DISPLAY 'SYK008 XCTLエラー RESP=' WS-RESPコード
           END-IF.

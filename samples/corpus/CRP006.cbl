      *----------------------------------------------------------*
      *  PROGRAM-ID : CRP006                                     *
      *  機能       : 商品コード入力画面処理（CICS疑似会話）      *
      *  処理概要   : マップCRPM01(マップセットCRPMAP1)から商品    *
      *               コードを受け取り、内容を検査する。エラー時  *
      *               はメッセージを画面表示し、正常時はCRP007へ  *
      *               制御を移す。                                *
      *  起動元     : CICSトランザクションCRP6                    *
      *  呼び出し先 : CRP007（EXEC CICS XCTL PROGRAM）            *
      *  用途       : 良好実装コーパス（誤検出計測用、defect無し）*
      *----------------------------------------------------------*

       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CRP006.
       AUTHOR.      CRP-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-商品照会マップ.
           05  WS-SHOCD-入力               PIC X(08).
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
               RECEIVE MAP('CRPM01')
                   MAPSET('CRPMAP1')
                   INTO(WS-商品照会マップ)
                   RESP(WS-RESPコード)
                   RESP2(WS-RESP2コード)
           END-EXEC
           IF WS-RESPコード NOT = DFHRESP(NORMAL)
               DISPLAY 'CRP006 RECEIVE MAPエラー RESP='
                       WS-RESPコード ' RESP2=' WS-RESP2コード
               MOVE 'E' TO WS-検査結果
           ELSE
               PERFORM 1000-商品コード検査
           END-IF
           IF WS-検査エラー
               PERFORM 2000-エラーメッセージ表示
           ELSE
               PERFORM 3000-次画面遷移
           END-IF.

       1000-商品コード検査.
           IF WS-SHOCD-入力 = SPACES
               MOVE 'E'                     TO WS-検査結果
               MOVE '商品コードが未入力です' TO WS-MSG-出力
           ELSE
               MOVE ' '                     TO WS-検査結果
           END-IF.

       2000-エラーメッセージ表示.
           EXEC CICS
               SEND MAP('CRPM01')
                   MAPSET('CRPMAP1')
                   FROM(WS-商品照会マップ)
                   RESP(WS-RESPコード)
                   RESP2(WS-RESP2コード)
           END-EXEC
           IF WS-RESPコード NOT = DFHRESP(NORMAL)
               DISPLAY 'CRP006 SEND MAPエラー RESP='
                       WS-RESPコード ' RESP2=' WS-RESP2コード
           END-IF
           EXEC CICS
               RETURN TRANSID('CRP6')
                   RESP(WS-RESPコード)
                   RESP2(WS-RESP2コード)
           END-EXEC
           IF WS-RESPコード NOT = DFHRESP(NORMAL)
               DISPLAY 'CRP006 RETURNエラー RESP='
                       WS-RESPコード ' RESP2=' WS-RESP2コード
           END-IF.

       3000-次画面遷移.
           EXEC CICS
               XCTL PROGRAM('CRP007')
                   COMMAREA(WS-SHOCD-入力)
                   LENGTH(8)
                   RESP(WS-RESPコード)
                   RESP2(WS-RESP2コード)
           END-EXEC
           IF WS-RESPコード NOT = DFHRESP(NORMAL)
               DISPLAY 'CRP006 XCTLエラー RESP='
                       WS-RESPコード ' RESP2=' WS-RESP2コード
           END-IF.

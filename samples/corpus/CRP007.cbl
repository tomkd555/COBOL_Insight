      *----------------------------------------------------------*
      *  PROGRAM-ID : CRP007                                     *
      *  機能       : 商品コード受付確認画面処理（CICS）          *
      *  処理概要   : CRP006からXCTLで遷移し、受け取った商品コード*
      *               を確認メッセージへ編集して画面表示する。    *
      *               最後に必ずEXEC CICS RETURNで制御を戻す。    *
      *  呼び出し元 : CRP006（EXEC CICS XCTL PROGRAM('CRP007')）  *
      *  用途       : 良好実装コーパス（誤検出計測用、defect無し）*
      *----------------------------------------------------------*

       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CRP007.
       AUTHOR.      CRP-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-商品照会マップ.
           05  WS-SHOCD-入力               PIC X(08).
           05  WS-MSG-出力                 PIC X(40).

       01  WS-応答コード.
           05  WS-RESPコード               PIC S9(08)    COMP.
           05  WS-RESP2コード              PIC S9(08)    COMP.

       LINKAGE SECTION.
       01  DFHCOMMAREA.
           05  CA-商品コード               PIC X(08).

       PROCEDURE DIVISION USING DFHCOMMAREA.
       0000-メイン処理.
           MOVE CA-商品コード TO WS-SHOCD-入力
           PERFORM 1000-確認メッセージ編集
           PERFORM 2000-確認画面表示
           EXEC CICS
               RETURN TRANSID('CRP6')
                   COMMAREA(CA-商品コード)
                   LENGTH(8)
                   RESP(WS-RESPコード)
                   RESP2(WS-RESP2コード)
           END-EXEC
           IF WS-RESPコード NOT = DFHRESP(NORMAL)
               DISPLAY 'CRP007 RETURNエラー RESP='
                       WS-RESPコード ' RESP2=' WS-RESP2コード
           END-IF.

       1000-確認メッセージ編集.
           STRING '商品コード '  CA-商品コード
                  ' を受け付けました' DELIMITED BY SIZE
               INTO WS-MSG-出力
           END-STRING.

       2000-確認画面表示.
           EXEC CICS
               SEND MAP('CRPM01')
                   MAPSET('CRPMAP1')
                   FROM(WS-商品照会マップ)
                   RESP(WS-RESPコード)
                   RESP2(WS-RESP2コード)
           END-EXEC
           IF WS-RESPコード NOT = DFHRESP(NORMAL)
               DISPLAY 'CRP007 SEND MAPエラー RESP='
                       WS-RESPコード ' RESP2=' WS-RESP2コード
           END-IF.

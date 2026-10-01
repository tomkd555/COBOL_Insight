000100*----------------------------------------------------------------*
000200* PROGRAM-ID : FLO050
000300* 機能       : 入金登録完了画面
000400* 処理概要   : 登録完了メッセージをマップ FLM01 に出し、制御を
000500*              CICS に返して疑似会話を終える。
000600* 起動元     : FLO040（EXEC CICS XCTL）
000700*              CICS トランザクション FL04
000800* 呼び出し先 : なし
000900*----------------------------------------------------------------*
001000 IDENTIFICATION DIVISION.
001100 PROGRAM-ID.  FLO050.
001200 AUTHOR.      FL-ONLINE-DEV.
001300*
001400 ENVIRONMENT DIVISION.
001500*
001600 DATA DIVISION.
001700 WORKING-STORAGE SECTION.
001800*
001900     COPY DFHBMSCA.
002000     COPY FLM010.
002100*
002200 01  WS-RESP                   PIC S9(08) COMP.
002300 01  WS-RESP2                  PIC S9(08) COMP.
002400 01  WS-MSG                    PIC X(60).
002500 01  WS-KEIYAKU-NO             PIC X(10).
002600*
002700 LINKAGE SECTION.
002800*
002900* FLO040 から渡される連絡域。FLO040 が成功時に渡す連絡域は
003000* 契約番号だけで、この DFHCOMMAREA より短い。
003100 01  DFHCOMMAREA.
003200     05  LK-CA-KEIYAKU-NO      PIC X(10).
003300     05  LK-CA-STATUS          PIC X(01).
003400     05  LK-CA-KOKYAKU-NM      PIC X(30).
003500*
003600 PROCEDURE DIVISION.
003700*----------------------------------------------------------------*
003800* 0000-MAIN  完了メッセージを出して CICS に制御を返す
003900*----------------------------------------------------------------*
004000 0000-MAIN.
004100     MOVE SPACES TO WS-KEIYAKU-NO
004200     IF EIBCALEN > 0
004300         MOVE LK-CA-KEIYAKU-NO TO WS-KEIYAKU-NO
004400     END-IF
004500     PERFORM 1000-KANRYO-GAMEN
004600     PERFORM 9000-END.
004700*----------------------------------------------------------------*
004800* 1000-KANRYO-GAMEN  登録完了メッセージを画面に出す
004900*----------------------------------------------------------------*
005000 1000-KANRYO-GAMEN.
005100     MOVE LOW-VALUES TO FLM01O
005200     MOVE WS-KEIYAKU-NO TO KEIYNOO
005300     MOVE DFHBMASK TO KEIYNOA
005400     MOVE DFHBMBRY TO MSGA
005500     MOVE '入金登録が終わりました。' TO MSGO
005600     EXEC CICS
005700         SEND MAP('FLM01') MAPSET('FLM010')
005800             FROM(FLM01O) ERASE
005900             RESP(WS-RESP) RESP2(WS-RESP2)
006000     END-EXEC
006100     IF WS-RESP NOT = DFHRESP(NORMAL)
006200         MOVE '画面の送信に失敗しました。' TO WS-MSG
006300         EXEC CICS
006400             SEND TEXT FROM(WS-MSG) LENGTH(60) ERASE NOHANDLE
006500         END-EXEC
006600     END-IF.
006700*----------------------------------------------------------------*
006800* 9000-END  制御を CICS に返して疑似会話を終える
006900*----------------------------------------------------------------*
007000 9000-END.
007100     EXEC CICS
007200         RETURN
007300     END-EXEC.

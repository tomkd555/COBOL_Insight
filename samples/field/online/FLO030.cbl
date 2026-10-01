000100*----------------------------------------------------------------*
000200* PROGRAM-ID : FLO030
000300* 機能       : 終了画面
000400* 処理概要   : 終了メッセージをマップ FLM01 に出し、制御を
000500*              CICS に返して疑似会話を終える。
000600* 起動元     : FLO010（EXEC CICS XCTL）
000700*              CICS トランザクション FL02
000800* 呼び出し先 : なし
000900*----------------------------------------------------------------*
001000 IDENTIFICATION DIVISION.
001100 PROGRAM-ID.  FLO030.
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
002500 01  WS-KEIYAKU-NO             PIC X(10).                         CHG25004
002600*
002700 LINKAGE SECTION.
002800*
002900* FLO010 から渡される連絡域の先頭 10 桁だけを使う
003000 01  DFHCOMMAREA.
003100     05  LK-CA-KEIYAKU-NO      PIC X(10).                         CHG25004
003200     05  FILLER                PIC X(743).
003300*
003400 PROCEDURE DIVISION.
003500*----------------------------------------------------------------*
003600* 0000-MAIN  終了メッセージを出して CICS に制御を返す
003700*----------------------------------------------------------------*
003800 0000-MAIN.
003900     MOVE SPACES TO WS-KEIYAKU-NO
004000     IF EIBCALEN > 0
004100         MOVE LK-CA-KEIYAKU-NO TO WS-KEIYAKU-NO                   CHG25004
004200     END-IF
004300     PERFORM 1000-SHURYO-GAMEN
004400     PERFORM 9000-END.
004500*----------------------------------------------------------------*
004600* 1000-SHURYO-GAMEN  終了メッセージを画面に出す
004700*----------------------------------------------------------------*
004800 1000-SHURYO-GAMEN.
004900     MOVE LOW-VALUES TO FLM01O
005000     MOVE WS-KEIYAKU-NO TO KEIYNOO
005100     MOVE DFHBMASK TO KEIYNOA
005200     MOVE DFHBMBRY TO MSGA
005300     MOVE '契約照会を終了しました。' TO MSGO
005400     EXEC CICS
005500         SEND MAP('FLM01') MAPSET('FLM010')
005600             FROM(FLM01O) ERASE
005700             RESP(WS-RESP) RESP2(WS-RESP2)
005800     END-EXEC
005900     IF WS-RESP NOT = DFHRESP(NORMAL)
006000         MOVE '画面の送信に失敗しました。' TO WS-MSG
006100         EXEC CICS
006200             SEND TEXT FROM(WS-MSG) LENGTH(60) ERASE NOHANDLE
006300         END-EXEC
006400     END-IF.
006500*----------------------------------------------------------------*
006600* 9000-END  制御を CICS に返して疑似会話を終える
006700*----------------------------------------------------------------*
006800 9000-END.
006900     EXEC CICS
007000         RETURN
007100     END-EXEC.

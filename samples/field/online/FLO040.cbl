000100*----------------------------------------------------------------*
000200* PROGRAM-ID : FLO040
000300* 機能       : 契約入金登録（CICS 疑似会話）
000400* 処理概要   : マップ FLM01・FLM02 で入金内容を受け取り、確定した
000500*              内容を FLO050 へ渡して登録画面に遷移する。
000600* 起動元     : CICS トランザクション FL03
000700* 呼び出し先 : FLO050（EXEC CICS XCTL）
000800*----------------------------------------------------------------*
000900 IDENTIFICATION DIVISION.
001000 PROGRAM-ID.  FLO040.
001100 AUTHOR.      FL-ONLINE-DEV.
001200*
001300 ENVIRONMENT DIVISION.
001400*
001500 DATA DIVISION.
001600 WORKING-STORAGE SECTION.
001700*
001800     COPY DFHBMSCA.
001900     COPY FLM010.
002000*
002100 01  WS-RESP                   PIC S9(08) COMP.
002200 01  WS-RESP2                  PIC S9(08) COMP.
002300 01  WS-MSG                    PIC X(60).
002400 01  WS-ERR-FLAG               PIC X(01) VALUE 'N'.
002500*
002600* 照会の記録を残す一時記憶データ（TSQ）
002700 01  WS-TSQ-REC                PIC X(100).
002800*
002900* FLO050 に渡す連絡域
003000 01  WS-COMMAREA.
003100     05  WS-CA-KEIYAKU-NO      PIC X(10).
003200     05  WS-CA-STATUS          PIC X(01).
003300     05  WS-CA-KOKYAKU-NM      PIC X(30).
003400*
003500* 契約番号だけを渡してしまう不正な連絡域
003600 01  WS-CA-SHORT.
003700     05  WS-CA-SHORT-KEIYAKU-NO PIC X(10).
003800*
003900 LINKAGE SECTION.
004000 01  DFHCOMMAREA.
004100     05  LK-CA-KEIYAKU-NO      PIC X(10).
004200     05  LK-CA-STATUS          PIC X(01).
004300     05  LK-CA-KOKYAKU-NM      PIC X(30).
004400*
004500 PROCEDURE DIVISION.
004600*----------------------------------------------------------------*
004700* 0000-MAIN  連絡域の内容で初回画面か受付済みかを判定する
004800*----------------------------------------------------------------*
004900 0000-MAIN.
005000     MOVE DFHCOMMAREA TO WS-COMMAREA
005100     EXEC CICS
005200         HANDLE ABEND LABEL(9500-ABEND-BAD)
005300     END-EXEC
005400     IF WS-CA-STATUS = 'R'
005500         PERFORM 1000-UKETSUKE
005600     ELSE
005700         PERFORM 1500-SHOKAI
005800     END-IF.
005900*----------------------------------------------------------------*
006000* 1500-SHOKAI  初回起動  入金内容入力画面を送る
006100*----------------------------------------------------------------*
006200 1500-SHOKAI.
006300     MOVE LOW-VALUES TO FLM01O
006400     MOVE DFHBMBRY TO MSGA
006500     MOVE '入金内容を入力してください。' TO MSGO
006600     EXEC CICS
006700         SEND MAP('FLM01') MAPSET('FLM010')
006800             FROM(FLM01O) ERASE
006900             RESP(WS-RESP) RESP2(WS-RESP2)
007000     END-EXEC
007100     IF WS-RESP NOT = DFHRESP(NORMAL)
007200         MOVE '入力画面の送信に失敗しました。' TO WS-MSG
007300         EXEC CICS
007400             SEND TEXT FROM(WS-MSG) LENGTH(60) ERASE NOHANDLE
007500         END-EXEC
007600     END-IF
007700     MOVE 'R' TO WS-CA-STATUS
007800     EXEC CICS
007900         RETURN TRANSID('FL03')
008000             COMMAREA(WS-COMMAREA)
008100             LENGTH(LENGTH OF WS-COMMAREA)
008200     END-EXEC.
008300*----------------------------------------------------------------*
008400* 1000-UKETSUKE  再入  入金内容と確認画面の入力を受け取る
008500*----------------------------------------------------------------*
008600 1000-UKETSUKE.
008700     EXEC CICS
008800         RECEIVE MAP('FLM01') MAPSET('FLM010')
008900             INTO(FLM01I)
009000             RESP(WS-RESP)
009100     END-EXEC
009200     IF WS-RESP NOT = DFHRESP(NORMAL)
009300         MOVE 'Y' TO WS-ERR-FLAG
009400     END-IF
009500     EXEC CICS
009600         HANDLE CONDITION MAPFAIL(8100-MAPFAIL)
009700     END-EXEC
009800     EXEC CICS
009900         RECEIVE MAP('FLM02') MAPSET('FLM010')
010000             INTO(FLM02I)
010100             RESP(WS-RESP)
010200     END-EXEC
010300     IF WS-RESP NOT = DFHRESP(NORMAL)
010400         MOVE 'Y' TO WS-ERR-FLAG
010500     END-IF
010600     IF WS-ERR-FLAG = 'Y'
010700         PERFORM 3000-ERROR-EXIT
010800     ELSE
010900         PERFORM 2000-SHORI
011000     END-IF.
011100*----------------------------------------------------------------*
011200* 2000-SHORI  入金内容を記録し、登録画面 FLO050 に制御を移す
011300*----------------------------------------------------------------*
011400 2000-SHORI.
011500     MOVE KEIYNO2I TO WS-CA-KEIYAKU-NO
011600     MOVE KOKYAKUI TO WS-CA-KOKYAKU-NM
011700     MOVE 'N' TO WS-CA-STATUS
011800     MOVE WS-COMMAREA TO WS-TSQ-REC
011900     EXEC CICS
012000         WRITEQ TS QUEUE('FLTSQ002')
012100             FROM(WS-TSQ-REC) LENGTH(100)
012200             NOHANDLE
012300     END-EXEC
012400     EXEC CICS
012500         DELETEQ TS QUEUE('FLTSQ002')
012600             NOHANDLE
012700     END-EXEC
012800     EXEC CICS
012900         HANDLE ABEND LABEL(9600-ABEND-OK)
013000     END-EXEC
013100     MOVE WS-CA-KEIYAKU-NO TO WS-CA-SHORT-KEIYAKU-NO
013200     EXEC CICS
013300         XCTL PROGRAM('FLO050')
013400             COMMAREA(WS-CA-SHORT)
013500             LENGTH(10)
013600             RESP(WS-RESP)
013700     END-EXEC
013800     IF WS-RESP NOT = DFHRESP(NORMAL)
013900         MOVE '登録画面への遷移に失敗しました。' TO WS-MSG
014000     END-IF.
014100*----------------------------------------------------------------*
014200* 3000-ERROR-EXIT  入力誤りのまま登録画面へ制御を移す
014300*----------------------------------------------------------------*
014400 3000-ERROR-EXIT.
014500     MOVE 'E' TO WS-CA-STATUS
014600     EXEC CICS
014700         XCTL PROGRAM('FLO050')
014800             COMMAREA(WS-COMMAREA)
014900             LENGTH(LENGTH OF WS-COMMAREA)
015000             RESP(WS-RESP)
015100     END-EXEC
015200     IF WS-RESP NOT = DFHRESP(NORMAL)
015300         MOVE '登録画面への遷移に失敗しました。' TO WS-MSG
015400     END-IF.
015500*----------------------------------------------------------------*
015600* 8100-MAPFAIL  MAPFAIL 発生時の受け口
015700*----------------------------------------------------------------*
015800 8100-MAPFAIL.
015900     MOVE 'Y' TO WS-ERR-FLAG.
016000*----------------------------------------------------------------*
016100* 9500-ABEND-BAD  異常終了の受け口  更新前なので取り消しは要らない
016200*----------------------------------------------------------------*
016300 9500-ABEND-BAD.
016400     MOVE '異常終了しました。' TO WS-MSG
016500     EXEC CICS
016600         SEND TEXT FROM(WS-MSG) LENGTH(60) ERASE NOHANDLE
016700     END-EXEC
016800     EXEC CICS
016900         RETURN
017000     END-EXEC.
017100*----------------------------------------------------------------*
017200* 9600-ABEND-OK  異常終了の受け口  更新後の取り消しをしてから戻る
017300*----------------------------------------------------------------*
017400 9600-ABEND-OK.
017500     EXEC CICS
017600         SYNCPOINT ROLLBACK
017700     END-EXEC
017800     EXEC CICS
017900         RETURN
018000     END-EXEC.

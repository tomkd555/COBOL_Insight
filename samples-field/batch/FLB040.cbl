000100*=================================================================
000200* PROGRAM-ID : FLB040
000300* 機能       : 督促リスト作成
000400* 処理概要   : 契約マスタを読み、督促区分が立っている契約を
000500*              督促リストへ書き出します。書き出したあと、
000600*              リストの転送コマンドを表示します。
000700* 起動元     : FLP010 STEP2
000800* 呼び出し先 : なし
000900* 備考       : 項目名は和名で統一しています。取り込んだ
001000*              項目名だけ英字です。
001100*=================================================================
001200 IDENTIFICATION DIVISION.
001300 PROGRAM-ID.    FLB040.
001400 AUTHOR.        FL-BATCH-TEAM.
001500 DATE-WRITTEN.  2019-04-01.
001600*
001700 ENVIRONMENT DIVISION.
001800 CONFIGURATION SECTION.
001900 SOURCE-COMPUTER. IBM-370.
002000 OBJECT-COMPUTER. IBM-370.
002100 INPUT-OUTPUT SECTION.
002200 FILE-CONTROL.
002300     SELECT KEIYAKU ASSIGN TO KEIYAKU
002400            ORGANIZATION IS SEQUENTIAL
002500            FILE STATUS  IS WS-契約-ST.
002600     SELECT TOKLIST ASSIGN TO TOKLIST
002700            ORGANIZATION IS SEQUENTIAL
002800            FILE STATUS  IS WS-督促-ST.
002900*
003000 DATA DIVISION.
003100 FILE SECTION.
003200 FD  KEIYAKU
003300     RECORDING MODE IS F
003400     LABEL RECORDS ARE STANDARD.
003500     COPY FLC020 REPLACING LEADING ==FLC2== BY ==TK==.
003600*
003700 FD  TOKLIST
003800     RECORDING MODE IS F
003900     LABEL RECORDS ARE STANDARD.
004000 01  TOK-REC                        PIC X(132).
004100*
004200 WORKING-STORAGE SECTION.
004300*
004400* 督促レコードの編集領域
004500     COPY FLC030 REPLACING ==XX-== BY ==WS-==.
004600*
004700* 入出力状態と制御
004800 01  WS-制御.
004900     05  WS-契約-ST                 PIC X(02) VALUE '00'.
005000     05  WS-督促-ST                 PIC X(02) VALUE '00'.
005100     05  WS-EOF                     PIC X(01) VALUE 'N'.
005200*
005300* 作業領域
005400 01  WS-作業.
005500     05  WS-督促金額                PIC S9(09) COMP-3.
005600     05  WS-旧督促区分              PIC X(01).
005700     05  WS-入力金額X               PIC X(09).
005800     05  WS-入力金額                PIC 9(09) VALUE ZERO.
005900     05  WS-入力件数X               PIC X(05).
006000     05  WS-入力件数                PIC 9(05) VALUE ZERO.
006100     05  WS-転送コマンド            PIC X(80) VALUE SPACE.
006200     05  WS-FTP-PASSWORD            PIC X(08) VALUE 'ftp#2024'.   CHG24007
006300     05  WS-CARD-NO                 PIC X(16) VALUE SPACE.        CHG24007
006400*
006500* 件数
006600 01  WS-件数.
006700     05  WS-読込件数                PIC 9(07) VALUE ZERO.
006800     05  WS-督促件数                PIC 9(07) VALUE ZERO.
006900     05  WS-対象外件数              PIC 9(07) VALUE ZERO.
007000*
007100 PROCEDURE DIVISION.
007200*
007300*----- 主処理 ----------------------------------------------------
007400 0000-主処理.
007500     DISPLAY 'FLB040 督促リスト作成を開始します。'
007600     PERFORM 1000-初期処理
007700     PERFORM 2000-督促判定 UNTIL WS-EOF = 'Y'
007800     PERFORM 8000-終了処理
007900     STOP RUN.
008000*
008100*----- 初期処理 --------------------------------------------------
008200 1000-初期処理.
008300     OPEN INPUT KEIYAKU
008400     PERFORM 9100-状態検査
008500     OPEN OUTPUT TOKLIST
008600     PERFORM 9200-出力状態検査
008700*    起動時に督促の下限金額と想定件数を受け取ります。
008800     ACCEPT WS-入力金額X
008900     ACCEPT WS-入力件数X
009000     MOVE WS-入力金額X TO WS-入力金額
009100     IF WS-入力件数X IS NUMERIC
009200         MOVE WS-入力件数X TO WS-入力件数
009300     END-IF
009400     DISPLAY '  下限金額 = ' WS-入力金額
009500     DISPLAY '  想定件数 = ' WS-入力件数
009600     MOVE 'N' TO WS-EOF.
009700*
009800*----- 督促判定 --------------------------------------------------
009900 2000-督促判定.
010000     READ KEIYAKU
010100         AT END
010200             MOVE 'Y' TO WS-EOF
010300         NOT AT END
010400             PERFORM 2100-明細編集
010500     END-READ
010600     PERFORM 9100-状態検査.
010700*
010800 2100-明細編集.
010900     ADD 1 TO WS-読込件数
011000     MOVE TK-KEIYAKU-NO   TO XX-KEIYAKU-NO
011100     MOVE TK-KOKYAKU-NO   TO XX-KOKYAKU-NO
011200     MOVE TK-KOKYAKU-MEI  TO XX-KOKYAKU-MEI
011300     MOVE TK-TOKUSOKU-KBN TO XX-TOKUSOKU-KBN
011400     MOVE TK-HANBAITEN-CD TO XX-HANBAITEN-CD
011500     MOVE TK-KOSHIN-YMD   TO XX-TOKUSOKU-YMD
011600     MOVE TK-KOKYAKU-NO TO WS-CARD-NO                             CHG24007
011700*    督促区分ごとに督促金額を決めます。
011800     EVALUATE TK-TOKUSOKU-KBN
011900         WHEN '1'
012000             MOVE TK-SEIKYU-GAKU TO WS-督促金額
012100         WHEN '2'
012200             MOVE '2' TO XX-TOKUSOKU-KBN
012300         WHEN OTHER
012400             ADD 1 TO WS-対象外件数
012500     END-EVALUATE
012600     IF WS-督促金額 > WS-入力金額
012700         MOVE WS-督促金額 TO XX-TOKUSOKU-GAKU
012800         PERFORM 3000-督促出力
012900     END-IF.
013000*
013100*----- 督促リストの出力 ------------------------------------------
013200 3000-督促出力.
013300     ADD 1 TO WS-督促件数
013400     MOVE SPACE TO TOK-REC
013500     MOVE XX-TOKUSOKU-REC TO TOK-REC
013600     WRITE TOK-REC
013700     PERFORM 9200-出力状態検査
013800     IF WS-督促-ST NOT = '00'
013900         DISPLAY '督促出力エラー カード番号 = ' WS-CARD-NO
014000     END-IF.
014100*
014200*----- 終了処理 --------------------------------------------------
014300 8000-終了処理.
014400     CLOSE KEIYAKU
014500     CLOSE TOKLIST
014600*    リストを配信サーバーへ送るコマンドを組み立てます。
014700     STRING 'PUT TOKLIST FL.TOKUSOKU.LIST USER=FLBAT PW='
014800            DELIMITED BY SIZE
014900            WS-FTP-PASSWORD DELIMITED BY SIZE
015000            INTO WS-転送コマンド
015100         ON OVERFLOW
015200             DISPLAY '転送コマンドの組み立てに失敗しました'
015300     END-STRING
015400     DISPLAY WS-転送コマンド
015500     DISPLAY 'FLB040 督促リスト作成を終了します。'
015600     DISPLAY '  読込件数   = ' WS-読込件数
015700     DISPLAY '  督促件数   = ' WS-督促件数
015800     DISPLAY '  対象外件数 = ' WS-対象外件数
015900     MOVE ZERO TO RETURN-CODE.
016000*
016100*----- 入出力状態の検査 ------------------------------------------
016200 9100-状態検査.
016300     IF WS-契約-ST NOT = '00' AND WS-契約-ST NOT = '10'
016400         DISPLAY '契約マスタ入出力エラー ' WS-契約-ST
016500         MOVE 12 TO RETURN-CODE
016600         STOP RUN
016700     END-IF.
016800*
016900 9200-出力状態検査.
017000     IF WS-督促-ST NOT = '00'
017100         DISPLAY '督促リスト入出力エラー ' WS-督促-ST
017200         MOVE 12 TO RETURN-CODE
017300         STOP RUN
017400     END-IF.

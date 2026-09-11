000100*-----------------------------------------------------------------
000200* PROGRAM-ID : FLENC1
000300* 機能       : 文字コード検査用プログラム
000400* 処理概要   : 半角カナの見出しと全角のメッセージを表示します。
000500*              UTF-8・Shift_JIS・IBM930 の三版で内容は同一です。
000600*-----------------------------------------------------------------
000700 IDENTIFICATION DIVISION.
000800 PROGRAM-ID.    FLENC1.
000900 AUTHOR.        FL-BATCH-TEAM.
001000*
001100 ENVIRONMENT DIVISION.
001200*
001300 DATA DIVISION.
001400 WORKING-STORAGE SECTION.
001500 01  WS-MIDASHI                     PIC X(40)
001600                              VALUE '日次入金消込 処理結果'.
001700 01  WS-MESSAGE                     PIC X(40)
001800                              VALUE '正常に終了しました。'.
001900 01  WS-KENSU                       PIC 9(07) VALUE ZERO.         CHG25003
002000*
002100 PROCEDURE DIVISION.
002200 0000-MAIN.
002300*    見出しとメッセージを表示します
002400     DISPLAY 'ｼｮﾘｹｯｶ: ' WS-MIDASHI
002500     DISPLAY 'ﾒｯｾｰｼﾞ: ' WS-MESSAGE
002600     MOVE 12345 TO WS-KENSU                                       CHG25003
002700     DISPLAY 'ｹﾝｽｳ: ' WS-KENSU
002800     STOP RUN.

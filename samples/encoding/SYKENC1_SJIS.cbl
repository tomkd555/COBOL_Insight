      *================================================================*
      *  PROGRAM-ID : SYKENC1                                         *
      *  文字コード検証用サンプルプログラム                             *
      *  日本語コメントと日本語混じりの見出しを含む。                    *
      *  本ファイルはUTF-8版・Shift_JIS版・EBCDIC版で内容を同一にし、    *
      *  文字コード判定・変換機能の検証に用いる。                        *
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  SYKENC1.
       AUTHOR.      SYK-SYSTEM-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-見出し                        PIC X(40)
                                            VALUE '日次処理結果'.
       01  WS-メッセージ                    PIC X(40)
                                            VALUE '正常に終了した。'.

       PROCEDURE DIVISION.
       0000-メイン処理.
      *    見出しとメッセージを画面へ表示する
           DISPLAY WS-見出し
           DISPLAY WS-メッセージ
           STOP RUN.

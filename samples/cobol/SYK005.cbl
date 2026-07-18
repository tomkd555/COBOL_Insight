      *================================================================*
      *  PROGRAM-ID : SYK005                                          *
      *  機能       : メッセージ編集・ログ出力共通処理（サブルーチン）  *
      *  処理概要   : 呼び出し元から受け取ったメッセージ区分に応じて     *
      *               メッセージを編集し、SYSOUTへ出力する。            *
      *  呼び出し元 : SYK006（静的CALL 'SYK005'）                      *
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  SYK005.
       AUTHOR.      SYK-SYSTEM-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-編集メッセージ                PIC X(100).

       LINKAGE SECTION.
       01  LK-メッセージ区分                PIC X(02).
       01  LK-メッセージ内容                PIC X(80).

       PROCEDURE DIVISION USING LK-メッセージ区分 LK-メッセージ内容.
       0000-メイン処理.
           EVALUATE LK-メッセージ区分
               WHEN 'E1'
                   PERFORM 1000-エラーメッセージ編集
               WHEN 'W1'
                   PERFORM 2000-警告メッセージ編集
               WHEN OTHER
                   PERFORM 3000-情報メッセージ編集
           END-EVALUATE
           GOBACK.

       1000-エラーメッセージ編集.
           STRING 'ERROR : ' LK-メッセージ内容 DELIMITED BY SIZE
               INTO WS-編集メッセージ
           END-STRING
           DISPLAY WS-編集メッセージ
           GOBACK.
           DISPLAY 'このメッセージは出力されない'.

       2000-警告メッセージ編集.
           STRING 'WARN  : ' LK-メッセージ内容 DELIMITED BY SIZE
               INTO WS-編集メッセージ
           END-STRING
           DISPLAY WS-編集メッセージ.

       3000-情報メッセージ編集.
           STRING 'INFO  : ' LK-メッセージ内容 DELIMITED BY SIZE
               INTO WS-編集メッセージ
           END-STRING
           DISPLAY WS-編集メッセージ.

      *================================================================*
      *  PROGRAM-ID : SYK009                                          *
      *  機能       : 受注確認メッセージ編集処理（CICS）                *
      *  処理概要   : SYK008からXCTLで遷移し、確認メッセージを編集する。 *
      *  呼び出し元 : SYK008（EXEC CICS XCTL PROGRAM('SYK009')）        *
      *================================================================*

       IDENTIFICATION DIVISION.
       PROGRAM-ID.  SYK009.
       AUTHOR.      SYK-SYSTEM-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-確認メッセージ               PIC X(40).

       PROCEDURE DIVISION.
       0000-メイン処理.
           MOVE '受注内容を確認しました' TO WS-確認メッセージ
           GOBACK.

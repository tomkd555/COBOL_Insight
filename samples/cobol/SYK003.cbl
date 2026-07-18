      *================================================================*
      *  PROGRAM-ID : SYK003                                          *
      *  機能       : 受注明細チェックサム検証（サブルーチン）          *
      *  処理概要   : 呼び出し元から受け取った受注レコードの明細金額     *
      *               を合計し、受注金額合計と一致するかを検証する。     *
      *  呼び出し元 : SYK001（静的CALL 'SYK003'）                      *
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  SYK003.
       AUTHOR.      SYK-SYSTEM-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-作業項目.
           05  WS-I                        PIC S9(04)    COMP.
           05  WS-合計                     PIC S9(09)V99 COMP-3
                                            VALUE ZERO.
           05  WS-旧チェック方式件数       PIC 9(05) VALUE ZERO.

       LINKAGE SECTION.
           COPY SYKCPY1.
       01  LK-チェック結果                 PIC X(01).

       PROCEDURE DIVISION USING SYK1-受注レコード LK-チェック結果.
       0000-メイン処理.
           MOVE ZERO TO WS-合計
           PERFORM VARYING WS-I FROM 1 BY 1
                   UNTIL WS-I > SYK1-明細件数
               ADD SYK1-金額(WS-I) TO WS-合計
           END-PERFORM
           IF WS-合計 = SYK1-受注金額合計
               MOVE '1' TO LK-チェック結果
           ELSE
               MOVE '0' TO LK-チェック結果
           END-IF
           GOBACK.

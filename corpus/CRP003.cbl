      *----------------------------------------------------------*
      *  PROGRAM-ID : CRP003                                     *
      *  機能       : 数量×単価の金額計算（サブルーチン）        *
      *  処理概要   : 呼び出し元から受け取った数量・単価から金額   *
      *               を計算する。桁あふれ時はRETURN-CODEに8を    *
      *               設定して直ちに戻る。                        *
      *  呼び出し元 : CRP004（静的CALL 'CRP003'）                 *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CRP003.
       AUTHOR.      CRP-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-作業項目.
           05  WS-計算結果                PIC S9(09)V99 COMP-3
                                            VALUE ZERO.

       LINKAGE SECTION.
       01  LK-数量                        PIC S9(05)    COMP-3.
       01  LK-単価                        PIC S9(07)V99 COMP-3.
       01  LK-計算金額                    PIC S9(09)V99 COMP-3.

       PROCEDURE DIVISION USING LK-数量 LK-単価 LK-計算金額.
       0000-メイン処理.
           MOVE ZERO TO WS-計算結果
           COMPUTE WS-計算結果 = LK-数量 * LK-単価
               ON SIZE ERROR
                   DISPLAY 'CRP003 金額計算でSIZE ERRORが発生'
                   MOVE 8 TO RETURN-CODE
                   GOBACK
           END-COMPUTE
           MOVE WS-計算結果 TO LK-計算金額
           MOVE 0 TO RETURN-CODE
           GOBACK.

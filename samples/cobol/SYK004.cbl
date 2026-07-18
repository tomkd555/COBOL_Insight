      *================================================================*
      *  PROGRAM-ID : SYK004                                          *
      *  機能       : 在庫引当判定（サブルーチン）                      *
      *  処理概要   : 呼び出し元から受け取った商品コード・要求数量を     *
      *               もとに在庫残数と比較し、引当可否を判定する。       *
      *  呼び出し元 : SYK002（動的CALL WS-PROG-NAME、実行時に            *
      *               'SYK004' を設定してCALLする）                    *
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  SYK004.
       AUTHOR.      SYK-SYSTEM-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-作業項目.
           05  WS-在庫残数                 PIC S9(07)    COMP-3.
           05  WS-引当数量                 PIC S9(07)    COMP-3.
           05  WS-判定区分                 PIC X(01).
               88  WS-在庫あり                  VALUE '1'.
               88  WS-在庫なし                  VALUE '2'.

       LINKAGE SECTION.
       01  LK-商品コード                    PIC X(08).
       01  LK-要求数量                      PIC S9(05)    COMP-3.
       01  LK-引当可否                      PIC X(01).

       PROCEDURE DIVISION USING LK-商品コード LK-要求数量 LK-引当可否.
       0000-メイン処理.
           PERFORM 1000-在庫確認
           PERFORM 2000-引当判定
           GOBACK.

       1000-在庫確認.
           IF LK-商品コード NOT = SPACES
               MOVE 999 TO WS-在庫残数
           END-IF.

       2000-引当判定.
           IF WS-在庫残数 >= LK-要求数量
               MOVE '1' TO LK-引当可否
           ELSE
               MOVE '0' TO LK-引当可否
           END-IF.

       9999-未使用処理.
           DISPLAY '在庫引当判定：旧ロジック（廃止済み）'
           MOVE ZERO TO WS-引当数量.

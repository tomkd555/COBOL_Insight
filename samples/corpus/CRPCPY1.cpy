      *----------------------------------------------------------*
      * CRPCPY1  入庫明細レコード　共通コピー句                  *
      * 入庫データ検証(CRP001)で使用する。REDEFINESは元項目と    *
      * 同じ長さ、明細行のOCCURSは件数項目CRP1-明細件数を        *
      * 上限チェックしたうえで使う。                              *
      *----------------------------------------------------------*
       01  CRP1-入庫レコード.
           05  CRP1-伝票番号           PIC X(10).
           05  CRP1-入庫日             PIC 9(08).
           05  CRP1-入庫日-西暦月日 REDEFINES CRP1-入庫日.
               10  CRP1-入庫年         PIC 9(04).
               10  CRP1-入庫月         PIC 9(02).
               10  CRP1-入庫日-日      PIC 9(02).
           05  CRP1-仕入先コード       PIC X(06).
           05  CRP1-入庫金額合計       PIC S9(09)V99 COMP-3.
           05  CRP1-明細件数           PIC S9(03)    COMP-3.
           05  CRP1-明細行 OCCURS 10 TIMES
                       INDEXED BY CRP1-IDX.
               10  CRP1-商品コード     PIC X(08).
               10  CRP1-数量           PIC S9(05)    COMP-3.
               10  CRP1-単価           PIC S9(07)V99 COMP-3.
               10  CRP1-金額           PIC S9(09)V99 COMP-3.
           05  CRP1-処理区分           PIC X(01).
               88  CRP1-新規登録           VALUE '1'.
               88  CRP1-訂正               VALUE '2'.
               88  CRP1-取消               VALUE '9'.

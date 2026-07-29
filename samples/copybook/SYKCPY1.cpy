      *----------------------------------------------------------*
      * SYKCPY1  受注明細レコード　共通コピー句                  *
      * 受注データ検証(SYK001)・受注データ登録(SYK002)・          *
      * チェックサム検証(SYK003)で使用する。接頭辞 SYK1- を       *
      * REPLACING で別の接頭辞へ置き換えて使う。                  *
      *----------------------------------------------------------*
       01  SYK1-受注レコード.
           05  SYK1-受注番号           PIC X(10).
           05  SYK1-受注日             PIC 9(08).
           05  SYK1-受注日-YMD REDEFINES SYK1-受注日.
               10  SYK1-受注日-年      PIC 9(04).
               10  SYK1-受注日-月      PIC 9(02).
               10  SYK1-受注日-日      PIC 9(02).
           05  SYK1-得意先コード       PIC X(06).
           05  SYK1-受注金額合計       PIC S9(09)V99 COMP-3.
           05  SYK1-明細件数           PIC S9(03)    COMP-3.
           05  SYK1-明細行 OCCURS 10 TIMES
                       INDEXED BY SYK1-IDX.
               10  SYK1-商品コード     PIC X(08).
               10  SYK1-数量           PIC S9(05)    COMP-3.
               10  SYK1-単価           PIC S9(07)V99 COMP-3.
               10  SYK1-金額           PIC S9(09)V99 COMP-3.
           05  SYK1-処理区分           PIC X(01).
               88  SYK1-新規登録           VALUE '1'.
               88  SYK1-訂正               VALUE '2'.
               88  SYK1-取消               VALUE '9'.

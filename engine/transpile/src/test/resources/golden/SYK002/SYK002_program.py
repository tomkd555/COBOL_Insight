"""SYK002 の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。
データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。
逐語対訳であり、演算や桁詰めの最適化は行わない。桁数・小数スケール・固定長の空白詰めは
フラット変数では再現せず、原文の PICTURE 句とレコードクラスのバイト列アクセサを正とする。"""


class SYK002Program:

    def __init__(self):
        self.IN1_受注番号 = ""
        self.IN1_受注日 = 0
        self.IN1_受注日_年 = 0
        self.IN1_受注日_月 = 0
        self.IN1_受注日_日 = 0
        self.IN1_得意先コード = ""
        self.IN1_受注金額合計 = 0
        self.IN1_明細件数 = 0
        self.IN1_商品コード = [""] * 10
        self.IN1_数量 = [0] * 10
        self.IN1_単価 = [0] * 10
        self.IN1_金額 = [0] * 10
        self.IN1_処理区分 = ""
        self.SYK2_受注番号 = ""
        self.SYK2_得意先コード = ""
        self.SYK2_受注日 = 0
        self.SYK2_受注金額合計 = 0
        self.SYK2_入金状況 = ""
        self.SYK2_登録日時 = ""
        self.SYK2_更新日時 = ""
        self.SYK2_予備領域 = ""
        self.WS_ORDVALID_STATUS = ""
        self.WS_MASTER_STATUS = ""
        self.WS_EOF_FLAG = ""  # VALUE 'N'
        self.WS_マスタ有無 = ""
        self.WS_PROG_NAME = ""
        self.WS_引当可否 = ""
        self.WS_要求数量 = 0
        self.WS_金額集計エリア = 0  # VALUE ZERO
        self.WS_新規件数 = 0  # VALUE ZERO
        self.WS_更新件数 = 0  # VALUE ZERO

    def call_program(self, name, *args):
        """CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。"""
        pass

    def _0000_メイン処理(self):
        self._1000_初期化処理()
        self._2000_受注データ読込()
        while not (self.WS_EOF_FLAG == "Y"):
            self._3000_登録メイン()
        self._8000_終了処理()
        return  # STOP RUN

    def _1000_初期化処理(self):
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # OPEN INPUT ORDVALID
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # OPEN I-O   ORDMSTR
        self.WS_PROG_NAME = "SYK004"

    def _2000_受注データ読込(self):
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # READ ORDVALID INTO IN1-受注レコード
        # AT END
        # MOVE 'Y' TO WS-EOF-FLAG
        # END-READ
        pass

    def _3000_登録メイン(self):
        self._3010_マスタ検索()
        if self.WS_マスタ有無 == "N":
            self._3020_新規登録処理()
        else:
            self._3030_更新登録処理()
        self._2000_受注データ読込()

    def _3010_マスタ検索(self):
        self.SYK2_受注番号 = self.IN1_受注番号
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # READ ORDMSTR
        # INVALID KEY
        # MOVE 'N' TO WS-マスタ有無
        # NOT INVALID KEY
        # MOVE 'Y' TO WS-マスタ有無
        # END-READ
        if self.WS_MASTER_STATUS == "93":
            self._9000_緊急再更新処理()

    def _3020_新規登録処理(self):
        self.SYK2_受注番号 = self.IN1_受注番号
        self.SYK2_得意先コード = self.IN1_得意先コード
        self.SYK2_受注日 = self.IN1_受注日
        self.SYK2_受注金額合計 = self.IN1_受注金額合計
        self.SYK2_入金状況 = "N"
        self.SYK2_登録日時 = " "
        self.SYK2_更新日時 = " "
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # WRITE SYK2-受注マスタレコード
        # INVALID KEY
        # DISPLAY 'SYK002 マスタ登録エラー ' SYK2-受注番号
        # END-WRITE
        self.WS_新規件数 = self.WS_新規件数 + 1
        self.WS_要求数量 = self.IN1_数量[1 - 1]
        # CALL WS-PROG-NAME USING IN1-商品コード(1), WS-要求数量, WS-引当可否 — BY REFERENCE 渡し。プログラム間の実呼出・値渡しは模擬しない
        self.call_program("WS-PROG-NAME", "IN1-商品コード(1)", "WS-要求数量", "WS-引当可否")

    def _3030_更新登録処理(self):
        self.WS_金額集計エリア = self.SYK2_受注金額合計
        # PERFORM THRU: _4000_マスタ更新処理 .. _4000_マスタ更新処理_EXIT
        self._4000_マスタ更新処理()
        self._4000_マスタ更新処理_EXIT()
        self.WS_更新件数 = self.WS_更新件数 + 1

    def _9000_緊急再更新処理(self):
        print("SYK002 レコードロック検出のため再更新を実施")
        # [直訳不能: GO TO 4010-マスタ書込 を以降の順次実行へ構造化]
        # GO TO 4010-マスタ書込
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ / GO TO 構造化による複製(元の段落と重複)]
        # REWRITE SYK2-受注マスタレコード
        pass  # EXIT

    def _4000_マスタ更新処理(self):
        self.SYK2_受注金額合計 = self.IN1_受注金額合計

    def _4010_マスタ書込(self):
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # REWRITE SYK2-受注マスタレコード
        pass

    def _4000_マスタ更新処理_EXIT(self):
        pass  # EXIT

    def _8000_終了処理(self):
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # CLOSE ORDVALID
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # CLOSE ORDMSTR
        print("SYK002 新規件数 = " + str(self.WS_新規件数))
        print("SYK002 更新件数 = " + str(self.WS_更新件数))

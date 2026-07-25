"""SYK003 の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。
データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。
逐語対訳であり、演算や桁詰めの最適化は行わない。桁数・小数スケール・固定長の空白詰めは
フラット変数では再現せず、原文の PICTURE 句とレコードクラスのバイト列アクセサを正とする。"""


class SYK003Program:

    def __init__(self):
        self.WS_I = 0
        self.WS_合計 = 0  # VALUE ZERO
        self.WS_旧チェック方式件数 = 0  # VALUE ZERO
        self.SYK1_受注番号 = ""
        self.SYK1_受注日 = 0
        self.SYK1_受注日_年 = 0
        self.SYK1_受注日_月 = 0
        self.SYK1_受注日_日 = 0
        self.SYK1_得意先コード = ""
        self.SYK1_受注金額合計 = 0
        self.SYK1_明細件数 = 0
        self.SYK1_商品コード = [""] * 10
        self.SYK1_数量 = [0] * 10
        self.SYK1_単価 = [0] * 10
        self.SYK1_金額 = [0] * 10
        self.SYK1_処理区分 = ""
        self.LK_チェック結果 = ""

    def call_program(self, name, *args):
        """CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。"""
        pass

    def _0000_メイン処理(self):
        self.WS_合計 = 0
        self.WS_I = 1
        while not (self.WS_I > self.SYK1_明細件数):
            self.WS_合計 = self.WS_合計 + self.SYK1_金額[self.WS_I - 1]
            self.WS_I = self.WS_I + 1
        if self.WS_合計 == self.SYK1_受注金額合計:
            self.LK_チェック結果 = "1"
        else:
            self.LK_チェック結果 = "0"
        return  # GOBACK

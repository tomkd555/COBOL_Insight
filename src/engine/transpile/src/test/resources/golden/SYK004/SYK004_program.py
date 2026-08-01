"""SYK004 の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。
データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。
逐語対訳であり、演算や桁詰めの最適化は行わない。桁数・小数スケール・固定長の空白詰めは
フラット変数では再現せず、原文の PICTURE 句とレコードクラスのバイト列アクセサを正とする。"""


class SYK004Program:

    def __init__(self):
        self.WS_在庫残数 = 0
        self.WS_引当数量 = 0
        self.WS_判定区分 = ""
        self.LK_商品コード = ""
        self.LK_要求数量 = 0
        self.LK_引当可否 = ""

    def call_program(self, name, *args):
        """CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。"""
        pass

    def _0000_メイン処理(self):
        self._1000_在庫確認()
        self._2000_引当判定()
        return  # GOBACK

    def _1000_在庫確認(self):
        if self.LK_商品コード != " ":
            self.WS_在庫残数 = 999

    def _2000_引当判定(self):
        if self.WS_在庫残数 >= self.LK_要求数量:
            self.LK_引当可否 = "1"
        else:
            self.LK_引当可否 = "0"

    def _9999_未使用処理(self):
        print("在庫引当判定：旧ロジック（廃止済み）")
        self.WS_引当数量 = 0

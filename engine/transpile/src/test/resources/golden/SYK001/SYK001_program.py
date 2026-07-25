"""SYK001 の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。
データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。
逐語対訳であり、演算や桁詰めの最適化は行わない。桁数・小数スケール・固定長の空白詰めは
フラット変数では再現せず、原文の PICTURE 句とレコードクラスのバイト列アクセサを正とする。"""


class SYK001Program:

    def __init__(self):
        self.ORD1_受注番号 = ""
        self.ORD1_受注日 = 0
        self.ORD1_受注日_年 = 0
        self.ORD1_受注日_月 = 0
        self.ORD1_受注日_日 = 0
        self.ORD1_得意先コード = ""
        self.ORD1_受注金額合計 = 0
        self.ORD1_明細件数 = 0
        self.ORD1_商品コード = [""] * 10
        self.ORD1_数量 = [0] * 10
        self.ORD1_単価 = [0] * 10
        self.ORD1_金額 = [0] * 10
        self.ORD1_処理区分 = ""
        self.VALID_REC = ""
        self.ERR_受注番号 = ""
        self.ERR_エラー内容 = ""
        self.FILLER = ""
        self.WS_ORDIN_STATUS = ""
        self.WS_ORDVALID_STATUS = ""
        self.WS_ORDERR_STATUS = ""
        self.WS_EOF_FLAG = ""  # VALUE 'N'
        self.WS_IDX = 0
        self.WS_検証金額 = 0
        self.WS_上限金額 = 0  # VALUE 10000000.00
        self.WS_印字用金額 = 0
        self.WS_合計チェック = 0  # VALUE ZERO
        self.WS_エラー件数 = 0  # VALUE ZERO
        self.WS_処理件数 = 0  # VALUE ZERO
        self.WS_チェック結果 = ""
        self.WS_エラーメッセージ = ""

    def call_program(self, name, *args):
        """CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。"""
        pass

    def _0000_メイン処理(self):
        self._1000_初期化処理()
        while not (self.WS_EOF_FLAG == "Y"):
            self._2000_受注データ処理()
        self._8000_終了処理()
        return  # STOP RUN

    def _1000_初期化処理(self):
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # OPEN INPUT  ORDIN
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # OPEN OUTPUT ORDVALID
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # OPEN OUTPUT ORDERR
        self._1100_受注データ読込()

    def _1100_受注データ読込(self):
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # READ ORDIN INTO ORD1-受注レコード
        # AT END
        # MOVE 'Y' TO WS-EOF-FLAG
        # END-READ
        pass

    def _2000_受注データ処理(self):
        self.WS_処理件数 = self.WS_処理件数 + 1
        self._2100_明細検証()
        self._2200_結果判定()
        self._1100_受注データ読込()

    def _2100_明細検証(self):
        if self.ORD1_処理区分 == "1":
            self._2110_新規登録検証()
        elif self.ORD1_処理区分 == "2":
            self._2120_訂正検証()
        elif self.ORD1_処理区分 == "9":
            pass  # CONTINUE
        else:
            self.WS_エラーメッセージ = "処理区分コード不正"

    def _2110_新規登録検証(self):
        self.WS_検証金額 = self.ORD1_受注金額合計
        # CALL SYK003 USING ORD1-受注レコード, WS-チェック結果 — BY REFERENCE 渡し。プログラム間の実呼出・値渡しは模擬しない
        self.call_program("SYK003", "ORD1-受注レコード", "WS-チェック結果")
        self.WS_合計チェック = 0
        self.WS_IDX = 1
        while not (self.WS_IDX > self.ORD1_明細件数):
            self.WS_合計チェック = self.WS_合計チェック + self.ORD1_金額[self.WS_IDX - 1]
            self.WS_IDX = self.WS_IDX + 1

    def _2120_訂正検証(self):
        self.WS_検証金額 = self.ORD1_受注金額合計

    def _2200_結果判定(self):
        if self.WS_検証金額 > self.WS_上限金額:
            self.WS_エラーメッセージ = "金額超過エラー"
            self.WS_エラー件数 = self.WS_エラー件数 + 1
            self.ERR_受注番号 = self.ORD1_受注番号
            self.ERR_エラー内容 = self.WS_エラーメッセージ
            # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
            # WRITE ERROR-REC
        else:
            self.WS_印字用金額 = self.ORD1_受注金額合計
            # [直訳不能: MOVE 送信項目が集団または未解決]
            # MOVE ORD1-受注レコード       TO VALID-REC
            # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
            # WRITE VALID-REC

    def _8000_終了処理(self):
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # CLOSE ORDIN
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # CLOSE ORDVALID
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # CLOSE ORDERR
        print("SYK001 処理件数   = " + str(self.WS_処理件数))
        print("SYK001 エラー件数 = " + str(self.WS_エラー件数))

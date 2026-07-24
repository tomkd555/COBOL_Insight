"""SYK005 の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。
データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。"""


class SYK005Program:

    def __init__(self):
        self.WS_編集メッセージ = ""
        self.LK_メッセージ区分 = ""
        self.LK_メッセージ内容 = ""

    def call_program(self, name, *args):
        """CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。"""
        pass

    def _0000_メイン処理(self):
        if self.LK_メッセージ区分 == "E1":
            self._1000_エラーメッセージ編集()
        elif self.LK_メッセージ区分 == "W1":
            self._2000_警告メッセージ編集()
        else:
            self._3000_情報メッセージ編集()
        return  # GOBACK

    def _1000_エラーメッセージ編集(self):
        # [直訳不能: 文字列操作(STRING/INSPECT 等)は逐語コメントのみ]
        # STRING 'ERROR : ' LK-メッセージ内容 DELIMITED BY SIZE
        # INTO WS-編集メッセージ
        # END-STRING
        print(self.WS_編集メッセージ)
        return  # GOBACK
        print("このメッセージは出力されない")

    def _2000_警告メッセージ編集(self):
        # [直訳不能: 文字列操作(STRING/INSPECT 等)は逐語コメントのみ]
        # STRING 'WARN  : ' LK-メッセージ内容 DELIMITED BY SIZE
        # INTO WS-編集メッセージ
        # END-STRING
        print(self.WS_編集メッセージ)

    def _3000_情報メッセージ編集(self):
        # [直訳不能: 文字列操作(STRING/INSPECT 等)は逐語コメントのみ]
        # STRING 'INFO  : ' LK-メッセージ内容 DELIMITED BY SIZE
        # INTO WS-編集メッセージ
        # END-STRING
        print(self.WS_編集メッセージ)

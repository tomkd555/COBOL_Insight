"""SYK008 の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。
データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。
逐語対訳であり、演算や桁詰めの最適化は行わない。桁数・小数スケール・固定長の空白詰めは
フラット変数では再現せず、原文の PICTURE 句とレコードクラスのバイト列アクセサを正とする。"""


class SYK008Program:

    def __init__(self):
        self.WS_ORDNO_入力 = ""
        self.WS_MSG_出力 = ""
        self.WS_検査結果 = ""
        self.WS_RESPコード = 0
        self.WS_RESP2コード = 0

    def call_program(self, name, *args):
        """CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。"""
        pass

    def _0000_メイン処理(self):
        # [直訳不能: EXEC CICS RECEIVE MAP は直訳不能 (INTO=WS-受注入力マップ, MAP=SYKM01, MAPSET=SYKMAP1)]
        # EXEC CICS
        # RECEIVE MAP('SYKM01')
        # MAPSET('SYKMAP1')
        # INTO(WS-受注入力マップ)
        # END-EXEC
        raise NotImplementedError("EXEC CICS RECEIVE MAP は直訳不能")
        self._1000_受注番号検査()
        if self.WS_検査結果 == "E":
            self._2000_エラーメッセージ表示()
        else:
            self._3000_次画面遷移()

    def _1000_受注番号検査(self):
        if WS-ORDNO-入力 == SPACES or not NUMERIC(WS-ORDNO-入力):
            self.WS_検査結果 = "E"
            self.WS_MSG_出力 = "受注番号が不正です"
        else:
            self.WS_検査結果 = " "

    def _2000_エラーメッセージ表示(self):
        # [直訳不能: EXEC CICS SEND MAP は直訳不能 (FROM=WS-受注入力マップ, MAP=SYKM99, MAPSET=SYKMAP1, RESP=WS-RESPコード, RESP2=WS-RESP2コード)]
        # EXEC CICS
        # SEND MAP('SYKM99')
        # MAPSET('SYKMAP1')
        # FROM(WS-受注入力マップ)
        # RESP(WS-RESPコード)
        # RESP2(WS-RESP2コード)
        # END-EXEC
        raise NotImplementedError("EXEC CICS SEND MAP は直訳不能")
        if self.WS_RESPコード != 0:
            print("SYK008 SEND MAPエラー RESP=" + str(self.WS_RESPコード))
        # [直訳不能: EXEC CICS RETURN は直訳不能 (RESP=WS-RESPコード, RESP2=WS-RESP2コード, TRANSID=SYK8)]
        # EXEC CICS
        # RETURN TRANSID('SYK8')
        # RESP(WS-RESPコード)
        # RESP2(WS-RESP2コード)
        # END-EXEC
        raise NotImplementedError("EXEC CICS RETURN は直訳不能")
        if self.WS_RESPコード != 0:
            print("SYK008 RETURNエラー RESP=" + str(self.WS_RESPコード))

    def _3000_次画面遷移(self):
        # [直訳不能: EXEC CICS XCTL は直訳不能 (PROGRAM=SYK009, RESP=WS-RESPコード, RESP2=WS-RESP2コード)]
        # EXEC CICS
        # XCTL PROGRAM('SYK009')
        # RESP(WS-RESPコード)
        # RESP2(WS-RESP2コード)
        # END-EXEC
        raise NotImplementedError("EXEC CICS XCTL は直訳不能")
        if self.WS_RESPコード != 0:
            print("SYK008 XCTLエラー RESP=" + str(self.WS_RESPコード))

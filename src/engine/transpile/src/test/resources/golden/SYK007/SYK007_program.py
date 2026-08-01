"""SYK007 の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。
データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。
逐語対訳であり、演算や桁詰めの最適化は行わない。桁数・小数スケール・固定長の空白詰めは
フラット変数では再現せず、原文の PICTURE 句とレコードクラスのバイト列アクセサを正とする。"""


class SYK007Program:

    def __init__(self):
        self.SYK3_商品コード = ""
        self.SYK3_倉庫コード = ""
        self.SYK3_在庫数量 = 0
        self.SYK3_引当可能数量 = 0
        self.SYK3_更新区分 = ""
        self.SYK3_処理日 = 0
        self.HOST_商品コード = ""
        self.HOST_倉庫コード = ""
        self.HOST_倉庫名 = ""
        self.HOST_引当数量 = 0
        self.WS_STKEXTR_STATUS = ""
        self.WS_EOF_FLAG = ""  # VALUE 'N'
        self.WS_処理件数 = 0  # VALUE ZERO
        self.WS_引当率 = 0

    def call_program(self, name, *args):
        """CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。"""
        pass
    # 作業部 SQL 指令(直訳不能・注記のみ): EXEC SQL INCLUDE SQLCA END-EXEC.
    # 作業部 SQL 指令(直訳不能・注記のみ): EXEC SQL BEGIN DECLARE SECTION END-EXEC.
    # 作業部 SQL 指令(直訳不能・注記のみ): EXEC SQL END DECLARE SECTION END-EXEC.

    def _0000_メイン処理(self):
        self._1000_初期化処理()
        while not (self.WS_EOF_FLAG == "Y"):
            self._2000_在庫照会処理()
        self._8000_終了処理()
        return  # STOP RUN

    def _1000_初期化処理(self):
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # OPEN INPUT STKEXTR
        self._1100_抽出データ読込()

    def _1100_抽出データ読込(self):
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # READ STKEXTR
        # AT END
        # MOVE 'Y' TO WS-EOF-FLAG
        # END-READ
        pass

    def _2000_在庫照会処理(self):
        self.WS_処理件数 = self.WS_処理件数 + 1
        self.HOST_商品コード = self.SYK3_商品コード
        self.HOST_倉庫コード = self.SYK3_倉庫コード
        # [直訳不能: EXEC SQL SELECT は直訳不能]
        # EXEC SQL
        # SELECT SOKO_NM INTO :HOST-倉庫名
        # FROM SYKDB.SOKOM
        # WHERE SOKO_CD = :HOST-倉庫コード
        # END-EXEC
        raise NotImplementedError("EXEC SQL SELECT は直訳不能")
        self._2100_引当率計算()
        self._2200_引当数量更新()
        self._1100_抽出データ読込()

    def _2100_引当率計算(self):
        self.WS_引当率 = (self.SYK3_引当可能数量 * 100) / self.SYK3_在庫数量

    def _2200_引当数量更新(self):
        self.HOST_引当数量 = self.SYK3_引当可能数量
        # [直訳不能: EXEC SQL UPDATE は直訳不能]
        # EXEC SQL
        # UPDATE SYKDB.ZAIKOM
        # SET HIKIATE_SU = :HOST-引当数量
        # WHERE SHOHIN_CD = :HOST-商品コード
        # AND SOKO_CD   = :HOST-倉庫コード
        # END-EXEC.
        raise NotImplementedError("EXEC SQL UPDATE は直訳不能")

    def _8000_終了処理(self):
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # CLOSE STKEXTR
        print("SYK007 処理件数 = " + str(self.WS_処理件数))

"""SYK006 の手続き部を逐語対訳した自動生成コード(非最適化・逐語優先)。
データ項目はフラットな変数として扱い、REDEFINES の別名共有と OCCURS の添字は簡約する。"""


class SYK006Program:

    def __init__(self):
        self.STKIN_商品コード = ""
        self.STKIN_倉庫コード = ""
        self.STKIN_増減数量 = 0
        self.STKIN_更新区分 = ""
        self.SYK3_商品コード = ""
        self.SYK3_倉庫コード = ""
        self.SYK3_在庫数量 = 0
        self.SYK3_引当可能数量 = 0
        self.SYK3_更新区分 = ""
        self.SYK3_処理日 = 0
        self.HOST_商品コード = ""
        self.HOST_倉庫コード = ""
        self.HOST_在庫数量 = 0
        self.HOST_引当数量 = 0
        self.HOST_増減数量 = 0
        self.WS_STKIN_STATUS = ""
        self.WS_STKEXTR_STATUS = ""
        self.WS_EOF_FLAG = ""  # VALUE 'N'
        self.WS_処理件数 = 0  # VALUE ZERO
        self.WS_エラー件数 = 0  # VALUE ZERO
        self.WS_エラー件数INDEX = 0  # VALUE ZERO
        self.WS_メッセージ区分 = ""
        self.WS_メッセージ内容 = ""
        self.WS_エラー商品 = [""] * 20

    def call_program(self, name, *args):
        """CALL のスタブ。プログラム間の実呼出・値渡しは模擬しない。"""
        pass
    # 作業部 SQL 指令(直訳不能・注記のみ): EXEC SQL INCLUDE SQLCA END-EXEC.
    # 作業部 SQL 指令(直訳不能・注記のみ): EXEC SQL BEGIN DECLARE SECTION END-EXEC.
    # 作業部 SQL 指令(直訳不能・注記のみ): EXEC SQL END DECLARE SECTION END-EXEC.

    def _0000_メイン処理(self):
        self._1000_初期化処理()
        while not (self.WS_EOF_FLAG == "Y"):
            self._2000_在庫更新処理()
        self._3000_抽出ファイル出力()
        self._8000_終了処理()
        return  # STOP RUN

    def _1000_初期化処理(self):
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # OPEN INPUT  STKIN
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # OPEN OUTPUT STKEXTR
        self._1100_トランザクション読込()

    def _1100_トランザクション読込(self):
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # READ STKIN INTO STKIN-レコード
        # AT END
        # MOVE 'Y' TO WS-EOF-FLAG
        # END-READ
        pass

    def _2000_在庫更新処理(self):
        self.WS_処理件数 = self.WS_処理件数 + 1
        self.HOST_商品コード = self.STKIN_商品コード
        self.HOST_倉庫コード = self.STKIN_倉庫コード
        # [直訳不能: EXEC SQL SELECT は直訳不能]
        # EXEC SQL
        # SELECT ZAIKO_SU INTO :HOST-在庫数量
        # FROM SYKDB.ZAIKOM
        # WHERE SHOHIN_CD = :HOST-商品コード
        # AND SOKO_CD   = :HOST-倉庫コード
        # END-EXEC
        raise NotImplementedError("EXEC SQL SELECT は直訳不能")
        if SQLCODE == 0:
            self._2100_在庫数更新()
        elif SQLCODE == 100:
            self._2200_在庫マスタ新規登録()
        else:
            self._2900_エラー商品登録()
        self._1100_トランザクション読込()

    def _2100_在庫数更新(self):
        self.HOST_増減数量 = self.STKIN_増減数量
        # [直訳不能: EXEC SQL UPDATE は直訳不能]
        # EXEC SQL
        # UPDATE SYKDB.ZAIKOM
        # SET ZAIKO_SU = ZAIKO_SU + :HOST-増減数量
        # WHERE SHOHIN_CD = :HOST-商品コード
        # AND SOKO_CD   = :HOST-倉庫コード
        # END-EXEC.
        raise NotImplementedError("EXEC SQL UPDATE は直訳不能")

    def _2200_在庫マスタ新規登録(self):
        self.HOST_増減数量 = self.STKIN_増減数量
        # [直訳不能: EXEC SQL INSERT は直訳不能]
        # EXEC SQL
        # INSERT INTO SYKDB.ZAIKOM
        # (SHOHIN_CD, SOKO_CD, ZAIKO_SU,
        # HIKIATE_SU, KOSHIN_BI)
        # VALUES (:HOST-商品コード, :HOST-倉庫コード,
        # :HOST-増減数量, 0, '00000000')
        # END-EXEC
        raise NotImplementedError("EXEC SQL INSERT は直訳不能")
        if SQLCODE != 0:
            self.WS_メッセージ区分 = "E1"
            self.WS_メッセージ内容 = "ZAIKO INSERT ERROR"
            # CALL SYK005 USING WS-メッセージ区分, WS-メッセージ内容 — BY REFERENCE 渡し。プログラム間の実呼出・値渡しは模擬しない
            self.call_program("SYK005", "WS-メッセージ区分", "WS-メッセージ内容")

    def _2900_エラー商品登録(self):
        self.WS_エラー件数 = self.WS_エラー件数 + 1
        self.WS_エラー件数INDEX = self.WS_エラー件数INDEX + 1
        self.WS_エラー商品[self.WS_エラー件数INDEX - 1] = self.STKIN_商品コード
        self.WS_メッセージ区分 = "E1"
        self.WS_メッセージ内容 = "ZAIKO SELECT ERROR"
        # CALL SYK005 USING WS-メッセージ区分, WS-メッセージ内容 — BY REFERENCE 渡し。プログラム間の実呼出・値渡しは模擬しない
        self.call_program("SYK005", "WS-メッセージ区分", "WS-メッセージ内容")

    def _3000_抽出ファイル出力(self):
        # [直訳不能: EXEC SQL DECLARE CURSOR は直訳不能]
        # EXEC SQL
        # DECLARE SYKZAIKOCUR CURSOR FOR
        # SELECT SHOHIN_CD, SOKO_CD, ZAIKO_SU, HIKIATE_SU
        # FROM SYKDB.ZAIKOM
        # END-EXEC
        raise NotImplementedError("EXEC SQL DECLARE CURSOR は直訳不能")
        # [直訳不能: EXEC SQL OPEN は直訳不能]
        # EXEC SQL
        # OPEN SYKZAIKOCUR
        # END-EXEC
        raise NotImplementedError("EXEC SQL OPEN は直訳不能")
        while not (SQLCODE == 100):
            self._3100_抽出データフェッチ()
        # [直訳不能: EXEC SQL CLOSE は直訳不能]
        # EXEC SQL
        # CLOSE SYKZAIKOCUR
        # END-EXEC.
        raise NotImplementedError("EXEC SQL CLOSE は直訳不能")

    def _3100_抽出データフェッチ(self):
        # [直訳不能: EXEC SQL FETCH は直訳不能]
        # EXEC SQL
        # FETCH SYKZAIKOCUR
        # INTO :HOST-商品コード, :HOST-倉庫コード,
        # :HOST-在庫数量, :HOST-引当数量
        # END-EXEC
        raise NotImplementedError("EXEC SQL FETCH は直訳不能")
        if SQLCODE == 0:
            self.SYK3_商品コード = self.HOST_商品コード
            self.SYK3_倉庫コード = self.HOST_倉庫コード
            self.SYK3_在庫数量 = self.HOST_在庫数量
            self.SYK3_引当可能数量 = self.HOST_引当数量
            self.SYK3_更新区分 = "1"
            # [直訳不能: MOVE の構文を解釈できない(集団/CORR/参照修正)]
            # MOVE FUNCTION CURRENT-DATE(1:8) TO SYK3-処理日
            # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
            # WRITE SYK3-在庫抽出レコード

    def _8000_終了処理(self):
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # CLOSE STKIN
        # [直訳不能: ファイル I/O は実行時ファイルモデルを持たないため注記のみ]
        # CLOSE STKEXTR
        print("SYK006 処理件数   = " + str(self.WS_処理件数))
        print("SYK006 エラー件数 = " + str(self.WS_エラー件数))

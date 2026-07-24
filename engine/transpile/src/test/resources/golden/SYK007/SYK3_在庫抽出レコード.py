"""SYK007 のデータ部レコード SYK3-在庫抽出レコード を逐語対訳した自動生成コード。"""
from cobol_runtime import (
    decode_alphanumeric,
    decode_binary,
    decode_packed,
    decode_zoned,
    encode_alphanumeric,
    encode_binary,
    encode_packed,
    encode_zoned,
)


class SYK3_在庫抽出レコード:
    """01 SYK3-在庫抽出レコード 総バイト長 29。"""

    LENGTH = 29

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(29)
        else:
            self._data = bytearray(data)
            if len(self._data) < 29:
                self._data.extend(b"\x00" * (29 - len(self._data)))

    @property
    def data(self):
        return self._data

    # SYK3-商品コード
    def get_SYK3_商品コード(self):
        return decode_alphanumeric(self._data, 0, 8)
    def set_SYK3_商品コード(self, value):
        encode_alphanumeric(self._data, 0, 8, value)

    # SYK3-倉庫コード
    def get_SYK3_倉庫コード(self):
        return decode_alphanumeric(self._data, 8, 4)
    def set_SYK3_倉庫コード(self, value):
        encode_alphanumeric(self._data, 8, 4, value)

    # SYK3-在庫数量
    def get_SYK3_在庫数量(self):
        return decode_packed(self._data, 12, 4)
    def set_SYK3_在庫数量(self, value):
        encode_packed(self._data, 12, 4, True, value)

    # SYK3-引当可能数量
    def get_SYK3_引当可能数量(self):
        return decode_packed(self._data, 16, 4)
    def set_SYK3_引当可能数量(self, value):
        encode_packed(self._data, 16, 4, True, value)

    # SYK3-更新区分
    def get_SYK3_更新区分(self):
        return decode_alphanumeric(self._data, 20, 1)
    def set_SYK3_更新区分(self, value):
        encode_alphanumeric(self._data, 20, 1, value)

    def is_SYK3_増加(self):
        return self.get_SYK3_更新区分() == "1"

    def is_SYK3_減少(self):
        return self.get_SYK3_更新区分() == "2"

    # SYK3-処理日
    def get_SYK3_処理日(self):
        return decode_zoned(self._data, 21, 8)
    def set_SYK3_処理日(self, value):
        encode_zoned(self._data, 21, 8, False, value)

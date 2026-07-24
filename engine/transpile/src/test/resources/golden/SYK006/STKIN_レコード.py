"""SYK006 のデータ部レコード STKIN-レコード を逐語対訳した自動生成コード。"""
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


class STKIN_レコード:
    """01 STKIN-レコード 総バイト長 17。"""

    LENGTH = 17

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(17)
        else:
            self._data = bytearray(data)
            if len(self._data) < 17:
                self._data.extend(b"\x00" * (17 - len(self._data)))

    @property
    def data(self):
        return self._data

    # STKIN-商品コード
    def get_STKIN_商品コード(self):
        return decode_alphanumeric(self._data, 0, 8)
    def set_STKIN_商品コード(self, value):
        encode_alphanumeric(self._data, 0, 8, value)

    # STKIN-倉庫コード
    def get_STKIN_倉庫コード(self):
        return decode_alphanumeric(self._data, 8, 4)
    def set_STKIN_倉庫コード(self, value):
        encode_alphanumeric(self._data, 8, 4, value)

    # STKIN-増減数量
    def get_STKIN_増減数量(self):
        return decode_packed(self._data, 12, 4)
    def set_STKIN_増減数量(self, value):
        encode_packed(self._data, 12, 4, True, value)

    # STKIN-更新区分
    def get_STKIN_更新区分(self):
        return decode_alphanumeric(self._data, 16, 1)
    def set_STKIN_更新区分(self, value):
        encode_alphanumeric(self._data, 16, 1, value)

    def is_STKIN_増加(self):
        return self.get_STKIN_更新区分() == "1"

    def is_STKIN_減少(self):
        return self.get_STKIN_更新区分() == "2"

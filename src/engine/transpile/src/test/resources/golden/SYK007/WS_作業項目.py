"""SYK007 のデータ部レコード WS-作業項目 を逐語対訳した自動生成コード。"""
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


class WS_作業項目:
    """01 WS-作業項目 総バイト長 8。"""

    LENGTH = 8

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(8)
        else:
            self._data = bytearray(data)
            if len(self._data) < 8:
                self._data.extend(b"\x00" * (8 - len(self._data)))

    @property
    def data(self):
        return self._data

    # WS-処理件数
    def get_WS_処理件数(self):
        return decode_zoned(self._data, 0, 5)
    def set_WS_処理件数(self, value):
        encode_zoned(self._data, 0, 5, False, value)

    # WS-引当率
    def get_WS_引当率(self):
        return decode_packed(self._data, 5, 3)
    def set_WS_引当率(self, value):
        encode_packed(self._data, 5, 3, True, value)

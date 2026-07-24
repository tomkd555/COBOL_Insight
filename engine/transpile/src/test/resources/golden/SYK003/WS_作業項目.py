"""SYK003 のデータ部レコード WS-作業項目 を逐語対訳した自動生成コード。"""
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
    """01 WS-作業項目 総バイト長 13。"""

    LENGTH = 13

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(13)
        else:
            self._data = bytearray(data)
            if len(self._data) < 13:
                self._data.extend(b"\x00" * (13 - len(self._data)))

    @property
    def data(self):
        return self._data

    # WS-I
    def get_WS_I(self):
        return decode_binary(self._data, 0, 2, True)
    def set_WS_I(self, value):
        encode_binary(self._data, 0, 2, True, value)

    # WS-合計
    def get_WS_合計(self):
        return decode_packed(self._data, 2, 6)
    def set_WS_合計(self, value):
        encode_packed(self._data, 2, 6, True, value)

    # WS-旧チェック方式件数
    def get_WS_旧チェック方式件数(self):
        return decode_zoned(self._data, 8, 5)
    def set_WS_旧チェック方式件数(self, value):
        encode_zoned(self._data, 8, 5, False, value)

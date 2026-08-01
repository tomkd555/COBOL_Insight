"""SYK002 のデータ部レコード WS-作業項目 を逐語対訳した自動生成コード。"""
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
    """01 WS-作業項目 総バイト長 17。"""

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

    # WS-PROG-NAME
    def get_WS_PROG_NAME(self):
        return decode_alphanumeric(self._data, 0, 8)
    def set_WS_PROG_NAME(self, value):
        encode_alphanumeric(self._data, 0, 8, value)

    # WS-引当可否
    def get_WS_引当可否(self):
        return decode_alphanumeric(self._data, 8, 1)
    def set_WS_引当可否(self, value):
        encode_alphanumeric(self._data, 8, 1, value)

    # WS-要求数量
    def get_WS_要求数量(self):
        return decode_packed(self._data, 9, 3)
    def set_WS_要求数量(self, value):
        encode_packed(self._data, 9, 3, True, value)

    # WS-金額集計エリア
    def get_WS_金額集計エリア(self):
        return decode_zoned(self._data, 12, 5)
    def set_WS_金額集計エリア(self, value):
        encode_zoned(self._data, 12, 5, False, value)

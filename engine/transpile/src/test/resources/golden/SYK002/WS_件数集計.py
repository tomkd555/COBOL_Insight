"""SYK002 のデータ部レコード WS-件数集計 を逐語対訳した自動生成コード。"""
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


class WS_件数集計:
    """01 WS-件数集計 総バイト長 10。"""

    LENGTH = 10

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(10)
        else:
            self._data = bytearray(data)
            if len(self._data) < 10:
                self._data.extend(b"\x00" * (10 - len(self._data)))

    @property
    def data(self):
        return self._data

    # WS-新規件数
    def get_WS_新規件数(self):
        return decode_zoned(self._data, 0, 5)
    def set_WS_新規件数(self, value):
        encode_zoned(self._data, 0, 5, False, value)

    # WS-更新件数
    def get_WS_更新件数(self):
        return decode_zoned(self._data, 5, 5)
    def set_WS_更新件数(self, value):
        encode_zoned(self._data, 5, 5, False, value)

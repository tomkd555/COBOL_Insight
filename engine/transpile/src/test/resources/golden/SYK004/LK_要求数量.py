"""SYK004 のデータ部レコード LK-要求数量 を逐語対訳した自動生成コード。"""
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


class LK_要求数量:
    """01 LK-要求数量 総バイト長 3。"""

    LENGTH = 3

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(3)
        else:
            self._data = bytearray(data)
            if len(self._data) < 3:
                self._data.extend(b"\x00" * (3 - len(self._data)))

    @property
    def data(self):
        return self._data

    # LK-要求数量
    def get_LK_要求数量(self):
        return decode_packed(self._data, 0, 3)
    def set_LK_要求数量(self, value):
        encode_packed(self._data, 0, 3, True, value)

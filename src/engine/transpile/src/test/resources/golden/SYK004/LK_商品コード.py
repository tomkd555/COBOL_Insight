"""SYK004 のデータ部レコード LK-商品コード を逐語対訳した自動生成コード。"""
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


class LK_商品コード:
    """01 LK-商品コード 総バイト長 8。"""

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

    # LK-商品コード
    def get_LK_商品コード(self):
        return decode_alphanumeric(self._data, 0, 8)
    def set_LK_商品コード(self, value):
        encode_alphanumeric(self._data, 0, 8, value)

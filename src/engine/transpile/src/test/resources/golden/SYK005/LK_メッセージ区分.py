"""SYK005 のデータ部レコード LK-メッセージ区分 を逐語対訳した自動生成コード。"""
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


class LK_メッセージ区分:
    """01 LK-メッセージ区分 総バイト長 2。"""

    LENGTH = 2

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(2)
        else:
            self._data = bytearray(data)
            if len(self._data) < 2:
                self._data.extend(b"\x00" * (2 - len(self._data)))

    @property
    def data(self):
        return self._data

    # LK-メッセージ区分
    def get_LK_メッセージ区分(self):
        return decode_alphanumeric(self._data, 0, 2)
    def set_LK_メッセージ区分(self, value):
        encode_alphanumeric(self._data, 0, 2, value)

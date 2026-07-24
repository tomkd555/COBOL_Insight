"""SYK005 のデータ部レコード LK-メッセージ内容 を逐語対訳した自動生成コード。"""
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


class LK_メッセージ内容:
    """01 LK-メッセージ内容 総バイト長 80。"""

    LENGTH = 80

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(80)
        else:
            self._data = bytearray(data)
            if len(self._data) < 80:
                self._data.extend(b"\x00" * (80 - len(self._data)))

    @property
    def data(self):
        return self._data

    # LK-メッセージ内容
    def get_LK_メッセージ内容(self):
        return decode_alphanumeric(self._data, 0, 80)
    def set_LK_メッセージ内容(self, value):
        encode_alphanumeric(self._data, 0, 80, value)

"""SYK003 のデータ部レコード LK-チェック結果 を逐語対訳した自動生成コード。"""
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


class LK_チェック結果:
    """01 LK-チェック結果 総バイト長 1。"""

    LENGTH = 1

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(1)
        else:
            self._data = bytearray(data)
            if len(self._data) < 1:
                self._data.extend(b"\x00" * (1 - len(self._data)))

    @property
    def data(self):
        return self._data

    # LK-チェック結果
    def get_LK_チェック結果(self):
        return decode_alphanumeric(self._data, 0, 1)
    def set_LK_チェック結果(self, value):
        encode_alphanumeric(self._data, 0, 1, value)

"""SYK006 のデータ部レコード HOST-倉庫コード を逐語対訳した自動生成コード。"""
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


class HOST_倉庫コード:
    """01 HOST-倉庫コード 総バイト長 4。"""

    LENGTH = 4

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(4)
        else:
            self._data = bytearray(data)
            if len(self._data) < 4:
                self._data.extend(b"\x00" * (4 - len(self._data)))

    @property
    def data(self):
        return self._data

    # HOST-倉庫コード
    def get_HOST_倉庫コード(self):
        return decode_alphanumeric(self._data, 0, 4)
    def set_HOST_倉庫コード(self, value):
        encode_alphanumeric(self._data, 0, 4, value)

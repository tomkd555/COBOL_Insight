"""SYK007 のデータ部レコード HOST-倉庫名 を逐語対訳した自動生成コード。"""
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


class HOST_倉庫名:
    """01 HOST-倉庫名 総バイト長 20。"""

    LENGTH = 20

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(20)
        else:
            self._data = bytearray(data)
            if len(self._data) < 20:
                self._data.extend(b"\x00" * (20 - len(self._data)))

    @property
    def data(self):
        return self._data

    # HOST-倉庫名
    def get_HOST_倉庫名(self):
        return decode_alphanumeric(self._data, 0, 20)
    def set_HOST_倉庫名(self, value):
        encode_alphanumeric(self._data, 0, 20, value)

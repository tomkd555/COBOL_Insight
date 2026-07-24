"""SYK006 のデータ部レコード HOST-増減数量 を逐語対訳した自動生成コード。"""
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


class HOST_増減数量:
    """01 HOST-増減数量 総バイト長 4。"""

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

    # HOST-増減数量
    def get_HOST_増減数量(self):
        return decode_packed(self._data, 0, 4)
    def set_HOST_増減数量(self, value):
        encode_packed(self._data, 0, 4, True, value)

"""SYK008 のデータ部レコード WS-応答コード を逐語対訳した自動生成コード。"""
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


class WS_応答コード:
    """01 WS-応答コード 総バイト長 8。"""

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

    # WS-RESPコード
    def get_WS_RESPコード(self):
        return decode_binary(self._data, 0, 4, True)
    def set_WS_RESPコード(self, value):
        encode_binary(self._data, 0, 4, True, value)

    # WS-RESP2コード
    def get_WS_RESP2コード(self):
        return decode_binary(self._data, 4, 4, True)
    def set_WS_RESP2コード(self, value):
        encode_binary(self._data, 4, 4, True, value)

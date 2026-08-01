"""SYK008 のデータ部レコード WS-受注入力マップ を逐語対訳した自動生成コード。"""
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


class WS_受注入力マップ:
    """01 WS-受注入力マップ 総バイト長 48。"""

    LENGTH = 48

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(48)
        else:
            self._data = bytearray(data)
            if len(self._data) < 48:
                self._data.extend(b"\x00" * (48 - len(self._data)))

    @property
    def data(self):
        return self._data

    # WS-ORDNO-入力
    def get_WS_ORDNO_入力(self):
        return decode_alphanumeric(self._data, 0, 8)
    def set_WS_ORDNO_入力(self, value):
        encode_alphanumeric(self._data, 0, 8, value)

    # WS-MSG-出力
    def get_WS_MSG_出力(self):
        return decode_alphanumeric(self._data, 8, 40)
    def set_WS_MSG_出力(self, value):
        encode_alphanumeric(self._data, 8, 40, value)

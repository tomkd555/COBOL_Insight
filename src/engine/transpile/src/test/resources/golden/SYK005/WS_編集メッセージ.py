"""SYK005 のデータ部レコード WS-編集メッセージ を逐語対訳した自動生成コード。"""
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


class WS_編集メッセージ:
    """01 WS-編集メッセージ 総バイト長 100。"""

    LENGTH = 100

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(100)
        else:
            self._data = bytearray(data)
            if len(self._data) < 100:
                self._data.extend(b"\x00" * (100 - len(self._data)))

    @property
    def data(self):
        return self._data

    # WS-編集メッセージ
    def get_WS_編集メッセージ(self):
        return decode_alphanumeric(self._data, 0, 100)
    def set_WS_編集メッセージ(self, value):
        encode_alphanumeric(self._data, 0, 100, value)

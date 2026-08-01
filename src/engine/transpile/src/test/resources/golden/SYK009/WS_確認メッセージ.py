"""SYK009 のデータ部レコード WS-確認メッセージ を逐語対訳した自動生成コード。"""
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


class WS_確認メッセージ:
    """01 WS-確認メッセージ 総バイト長 40。"""

    LENGTH = 40

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(40)
        else:
            self._data = bytearray(data)
            if len(self._data) < 40:
                self._data.extend(b"\x00" * (40 - len(self._data)))

    @property
    def data(self):
        return self._data

    # WS-確認メッセージ
    def get_WS_確認メッセージ(self):
        return decode_alphanumeric(self._data, 0, 40)
    def set_WS_確認メッセージ(self, value):
        encode_alphanumeric(self._data, 0, 40, value)

"""SYK006 のデータ部レコード WS-エラー商品テーブル を逐語対訳した自動生成コード。"""
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


class WS_エラー商品テーブル:
    """01 WS-エラー商品テーブル 総バイト長 160。"""

    LENGTH = 160

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(160)
        else:
            self._data = bytearray(data)
            if len(self._data) < 160:
                self._data.extend(b"\x00" * (160 - len(self._data)))

    @property
    def data(self):
        return self._data

    # WS-エラー商品
    def get_WS_エラー商品(self):
        return decode_alphanumeric(self._data, 0, 8)
    def set_WS_エラー商品(self, value):
        encode_alphanumeric(self._data, 0, 8, value)

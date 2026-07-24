"""SYK006 のデータ部レコード WS-作業項目 を逐語対訳した自動生成コード。"""
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


class WS_作業項目:
    """01 WS-作業項目 総バイト長 94。"""

    LENGTH = 94

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(94)
        else:
            self._data = bytearray(data)
            if len(self._data) < 94:
                self._data.extend(b"\x00" * (94 - len(self._data)))

    @property
    def data(self):
        return self._data

    # WS-処理件数
    def get_WS_処理件数(self):
        return decode_zoned(self._data, 0, 5)
    def set_WS_処理件数(self, value):
        encode_zoned(self._data, 0, 5, False, value)

    # WS-エラー件数
    def get_WS_エラー件数(self):
        return decode_zoned(self._data, 5, 5)
    def set_WS_エラー件数(self, value):
        encode_zoned(self._data, 5, 5, False, value)

    # WS-エラー件数INDEX
    def get_WS_エラー件数INDEX(self):
        return decode_binary(self._data, 10, 2, True)
    def set_WS_エラー件数INDEX(self, value):
        encode_binary(self._data, 10, 2, True, value)

    # WS-メッセージ区分
    def get_WS_メッセージ区分(self):
        return decode_alphanumeric(self._data, 12, 2)
    def set_WS_メッセージ区分(self, value):
        encode_alphanumeric(self._data, 12, 2, value)

    # WS-メッセージ内容
    def get_WS_メッセージ内容(self):
        return decode_alphanumeric(self._data, 14, 80)
    def set_WS_メッセージ内容(self, value):
        encode_alphanumeric(self._data, 14, 80, value)

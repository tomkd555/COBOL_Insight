"""SYK001 のデータ部レコード WS-作業項目 を逐語対訳した自動生成コード。"""
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
    """01 WS-作業項目 総バイト長 37。"""

    LENGTH = 37

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(37)
        else:
            self._data = bytearray(data)
            if len(self._data) < 37:
                self._data.extend(b"\x00" * (37 - len(self._data)))

    @property
    def data(self):
        return self._data

    # WS-IDX
    def get_WS_IDX(self):
        return decode_binary(self._data, 0, 2, True)
    def set_WS_IDX(self, value):
        encode_binary(self._data, 0, 2, True, value)

    # WS-検証金額
    def get_WS_検証金額(self):
        return decode_packed(self._data, 2, 6)
    def set_WS_検証金額(self, value):
        encode_packed(self._data, 2, 6, True, value)

    # WS-上限金額
    def get_WS_上限金額(self):
        return decode_packed(self._data, 8, 6)
    def set_WS_上限金額(self, value):
        encode_packed(self._data, 8, 6, True, value)

    # WS-印字用金額
    def get_WS_印字用金額(self):
        return decode_zoned(self._data, 14, 6)
    def set_WS_印字用金額(self, value):
        encode_zoned(self._data, 14, 6, False, value)

    # WS-合計チェック
    def get_WS_合計チェック(self):
        return decode_packed(self._data, 20, 6)
    def set_WS_合計チェック(self, value):
        encode_packed(self._data, 20, 6, True, value)

    # WS-エラー件数
    def get_WS_エラー件数(self):
        return decode_zoned(self._data, 26, 5)
    def set_WS_エラー件数(self, value):
        encode_zoned(self._data, 26, 5, False, value)

    # WS-処理件数
    def get_WS_処理件数(self):
        return decode_zoned(self._data, 31, 5)
    def set_WS_処理件数(self, value):
        encode_zoned(self._data, 31, 5, False, value)

    # WS-チェック結果
    def get_WS_チェック結果(self):
        return decode_alphanumeric(self._data, 36, 1)
    def set_WS_チェック結果(self, value):
        encode_alphanumeric(self._data, 36, 1, value)

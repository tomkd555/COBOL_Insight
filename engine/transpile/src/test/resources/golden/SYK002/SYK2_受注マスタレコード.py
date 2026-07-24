"""SYK002 のデータ部レコード SYK2-受注マスタレコード を逐語対訳した自動生成コード。"""
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


class SYK2_受注マスタレコード:
    """01 SYK2-受注マスタレコード 総バイト長 79。"""

    LENGTH = 79

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(79)
        else:
            self._data = bytearray(data)
            if len(self._data) < 79:
                self._data.extend(b"\x00" * (79 - len(self._data)))

    @property
    def data(self):
        return self._data

    # SYK2-受注番号
    def get_SYK2_受注番号(self):
        return decode_alphanumeric(self._data, 0, 10)
    def set_SYK2_受注番号(self, value):
        encode_alphanumeric(self._data, 0, 10, value)

    # SYK2-得意先コード
    def get_SYK2_得意先コード(self):
        return decode_alphanumeric(self._data, 10, 6)
    def set_SYK2_得意先コード(self, value):
        encode_alphanumeric(self._data, 10, 6, value)

    # SYK2-受注日
    def get_SYK2_受注日(self):
        return decode_zoned(self._data, 16, 8)
    def set_SYK2_受注日(self, value):
        encode_zoned(self._data, 16, 8, False, value)

    # SYK2-受注金額合計
    def get_SYK2_受注金額合計(self):
        return decode_packed(self._data, 24, 6)
    def set_SYK2_受注金額合計(self, value):
        encode_packed(self._data, 24, 6, True, value)

    # SYK2-入金状況
    def get_SYK2_入金状況(self):
        return decode_alphanumeric(self._data, 30, 1)
    def set_SYK2_入金状況(self, value):
        encode_alphanumeric(self._data, 30, 1, value)

    def is_SYK2_入金済(self):
        return self.get_SYK2_入金状況() == "Y"

    def is_SYK2_未入金(self):
        return self.get_SYK2_入金状況() == "N"

    # SYK2-登録日時
    def get_SYK2_登録日時(self):
        return decode_alphanumeric(self._data, 31, 14)
    def set_SYK2_登録日時(self, value):
        encode_alphanumeric(self._data, 31, 14, value)

    # SYK2-更新日時
    def get_SYK2_更新日時(self):
        return decode_alphanumeric(self._data, 45, 14)
    def set_SYK2_更新日時(self, value):
        encode_alphanumeric(self._data, 45, 14, value)

    # SYK2-予備領域
    def get_SYK2_予備領域(self):
        return decode_alphanumeric(self._data, 59, 20)
    def set_SYK2_予備領域(self, value):
        encode_alphanumeric(self._data, 59, 20, value)

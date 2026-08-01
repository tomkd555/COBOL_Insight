"""SYK001 のデータ部レコード ORD1-受注レコード を逐語対訳した自動生成コード。"""
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


class ORD1_受注レコード:
    """01 ORD1-受注レコード 総バイト長 253。"""

    LENGTH = 253

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(253)
        else:
            self._data = bytearray(data)
            if len(self._data) < 253:
                self._data.extend(b"\x00" * (253 - len(self._data)))

    @property
    def data(self):
        return self._data

    # ORD1-受注番号
    def get_ORD1_受注番号(self):
        return decode_alphanumeric(self._data, 0, 10)
    def set_ORD1_受注番号(self, value):
        encode_alphanumeric(self._data, 0, 10, value)

    # ORD1-受注日
    def get_ORD1_受注日(self):
        return decode_zoned(self._data, 10, 8)
    def set_ORD1_受注日(self, value):
        encode_zoned(self._data, 10, 8, False, value)

    # ORD1-受注日-YMD REDEFINES ORD1-受注日 (同一オフセット 10)

    # ORD1-受注日-年
    def get_ORD1_受注日_年(self):
        return decode_zoned(self._data, 10, 4)
    def set_ORD1_受注日_年(self, value):
        encode_zoned(self._data, 10, 4, False, value)

    # ORD1-受注日-月
    def get_ORD1_受注日_月(self):
        return decode_zoned(self._data, 14, 2)
    def set_ORD1_受注日_月(self, value):
        encode_zoned(self._data, 14, 2, False, value)

    # ORD1-受注日-日
    def get_ORD1_受注日_日(self):
        return decode_zoned(self._data, 16, 2)
    def set_ORD1_受注日_日(self, value):
        encode_zoned(self._data, 16, 2, False, value)

    # ORD1-得意先コード
    def get_ORD1_得意先コード(self):
        return decode_alphanumeric(self._data, 18, 6)
    def set_ORD1_得意先コード(self, value):
        encode_alphanumeric(self._data, 18, 6, value)

    # ORD1-受注金額合計
    def get_ORD1_受注金額合計(self):
        return decode_packed(self._data, 24, 6)
    def set_ORD1_受注金額合計(self, value):
        encode_packed(self._data, 24, 6, True, value)

    # ORD1-明細件数
    def get_ORD1_明細件数(self):
        return decode_packed(self._data, 30, 2)
    def set_ORD1_明細件数(self, value):
        encode_packed(self._data, 30, 2, True, value)

    # ORD1-明細行 OCCURS 10 TIMES (要素長 22, offset 32)

    # ORD1-商品コード
    def get_ORD1_商品コード(self, i0):
        return decode_alphanumeric(self._data, 32 + i0 * 22, 8)
    def set_ORD1_商品コード(self, i0, value):
        encode_alphanumeric(self._data, 32 + i0 * 22, 8, value)

    # ORD1-数量
    def get_ORD1_数量(self, i0):
        return decode_packed(self._data, 40 + i0 * 22, 3)
    def set_ORD1_数量(self, i0, value):
        encode_packed(self._data, 40 + i0 * 22, 3, True, value)

    # ORD1-単価
    def get_ORD1_単価(self, i0):
        return decode_packed(self._data, 43 + i0 * 22, 5)
    def set_ORD1_単価(self, i0, value):
        encode_packed(self._data, 43 + i0 * 22, 5, True, value)

    # ORD1-金額
    def get_ORD1_金額(self, i0):
        return decode_packed(self._data, 48 + i0 * 22, 6)
    def set_ORD1_金額(self, i0, value):
        encode_packed(self._data, 48 + i0 * 22, 6, True, value)

    # ORD1-処理区分
    def get_ORD1_処理区分(self):
        return decode_alphanumeric(self._data, 252, 1)
    def set_ORD1_処理区分(self, value):
        encode_alphanumeric(self._data, 252, 1, value)

    def is_ORD1_新規登録(self):
        return self.get_ORD1_処理区分() == "1"

    def is_ORD1_訂正(self):
        return self.get_ORD1_処理区分() == "2"

    def is_ORD1_取消(self):
        return self.get_ORD1_処理区分() == "9"

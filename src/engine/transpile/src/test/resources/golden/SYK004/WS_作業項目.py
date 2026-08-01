"""SYK004 のデータ部レコード WS-作業項目 を逐語対訳した自動生成コード。"""
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
    """01 WS-作業項目 総バイト長 9。"""

    LENGTH = 9

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(9)
        else:
            self._data = bytearray(data)
            if len(self._data) < 9:
                self._data.extend(b"\x00" * (9 - len(self._data)))

    @property
    def data(self):
        return self._data

    # WS-在庫残数
    def get_WS_在庫残数(self):
        return decode_packed(self._data, 0, 4)
    def set_WS_在庫残数(self, value):
        encode_packed(self._data, 0, 4, True, value)

    # WS-引当数量
    def get_WS_引当数量(self):
        return decode_packed(self._data, 4, 4)
    def set_WS_引当数量(self, value):
        encode_packed(self._data, 4, 4, True, value)

    # WS-判定区分
    def get_WS_判定区分(self):
        return decode_alphanumeric(self._data, 8, 1)
    def set_WS_判定区分(self, value):
        encode_alphanumeric(self._data, 8, 1, value)

    def is_WS_在庫あり(self):
        return self.get_WS_判定区分() == "1"

    def is_WS_在庫なし(self):
        return self.get_WS_判定区分() == "2"

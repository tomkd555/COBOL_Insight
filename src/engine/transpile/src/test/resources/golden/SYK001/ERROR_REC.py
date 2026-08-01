"""SYK001 のデータ部レコード ERROR-REC を逐語対訳した自動生成コード。"""
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


class ERROR_REC:
    """01 ERROR-REC 総バイト長 200。"""

    LENGTH = 200

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(200)
        else:
            self._data = bytearray(data)
            if len(self._data) < 200:
                self._data.extend(b"\x00" * (200 - len(self._data)))

    @property
    def data(self):
        return self._data

    # ERR-受注番号
    def get_ERR_受注番号(self):
        return decode_alphanumeric(self._data, 0, 10)
    def set_ERR_受注番号(self, value):
        encode_alphanumeric(self._data, 0, 10, value)

    # ERR-エラー内容
    def get_ERR_エラー内容(self):
        return decode_alphanumeric(self._data, 10, 40)
    def set_ERR_エラー内容(self, value):
        encode_alphanumeric(self._data, 10, 40, value)

    # FILLER
    def get_FILLER(self):
        return decode_alphanumeric(self._data, 50, 150)
    def set_FILLER(self, value):
        encode_alphanumeric(self._data, 50, 150, value)

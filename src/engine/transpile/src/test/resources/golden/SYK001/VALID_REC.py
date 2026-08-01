"""SYK001 のデータ部レコード VALID-REC を逐語対訳した自動生成コード。"""
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


class VALID_REC:
    """01 VALID-REC 総バイト長 253。"""

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

    # VALID-REC
    def get_VALID_REC(self):
        return decode_alphanumeric(self._data, 0, 253)
    def set_VALID_REC(self, value):
        encode_alphanumeric(self._data, 0, 253, value)

"""SYK002 のデータ部レコード WS-ファイル状態 を逐語対訳した自動生成コード。"""
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


class WS_ファイル状態:
    """01 WS-ファイル状態 総バイト長 4。"""

    LENGTH = 4

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(4)
        else:
            self._data = bytearray(data)
            if len(self._data) < 4:
                self._data.extend(b"\x00" * (4 - len(self._data)))

    @property
    def data(self):
        return self._data

    # WS-ORDVALID-STATUS
    def get_WS_ORDVALID_STATUS(self):
        return decode_alphanumeric(self._data, 0, 2)
    def set_WS_ORDVALID_STATUS(self, value):
        encode_alphanumeric(self._data, 0, 2, value)

    # WS-MASTER-STATUS
    def get_WS_MASTER_STATUS(self):
        return decode_alphanumeric(self._data, 2, 2)
    def set_WS_MASTER_STATUS(self, value):
        encode_alphanumeric(self._data, 2, 2, value)

"""SYK001 のデータ部レコード WS-制御フラグ を逐語対訳した自動生成コード。"""
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


class WS_制御フラグ:
    """01 WS-制御フラグ 総バイト長 1。"""

    LENGTH = 1

    def __init__(self, data=None):
        if data is None:
            self._data = bytearray(1)
        else:
            self._data = bytearray(data)
            if len(self._data) < 1:
                self._data.extend(b"\x00" * (1 - len(self._data)))

    @property
    def data(self):
        return self._data

    # WS-EOF-FLAG
    def get_WS_EOF_FLAG(self):
        return decode_alphanumeric(self._data, 0, 1)
    def set_WS_EOF_FLAG(self, value):
        encode_alphanumeric(self._data, 0, 1, value)

    def is_WS_EOF(self):
        return self.get_WS_EOF_FLAG() == "Y"

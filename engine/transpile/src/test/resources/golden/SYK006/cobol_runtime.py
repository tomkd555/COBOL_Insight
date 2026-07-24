# COBOL データ項目の値とバイト列を相互変換する実行時コーデック(自動生成・入力非依存)。
# 符号規約: COMP-3 とゾーン10進は signed 正=0xC・負=0xD・unsigned=0xF、復号は末尾
# ハーフバイト(またはゾーン)が 0xD のとき負とする。BINARY はビッグエンディアン2の補数。
# 英数字は ISO-8859-1(1バイト1文字)で符号化し、不足は空白(0x20)で右詰めする。


def _nibble(data, offset, index):
    byte = data[offset + index // 2]
    return (byte >> 4) if index % 2 == 0 else (byte & 0x0F)


def decode_packed(data, offset, length):
    total_nibbles = length * 2
    digits = "".join(str(_nibble(data, offset, n)) for n in range(total_nibbles - 1))
    magnitude = int(digits) if digits else 0
    return -magnitude if _nibble(data, offset, total_nibbles - 1) == 0x0D else magnitude


def encode_packed(data, offset, length, signed, value):
    total_nibbles = length * 2
    nibbles = [0] * total_nibbles
    nibbles[-1] = (0x0D if value < 0 else 0x0C) if signed else 0x0F
    pos = total_nibbles - 2
    for ch in reversed(str(abs(value))):
        if pos < 0:
            break
        nibbles[pos] = ord(ch) - ord("0")
        pos -= 1
    for b in range(length):
        data[offset + b] = (nibbles[2 * b] << 4) | nibbles[2 * b + 1]


def decode_zoned(data, offset, length):
    digits = "".join(str(data[offset + i] & 0x0F) for i in range(length))
    magnitude = int(digits) if digits else 0
    last_zone = (data[offset + length - 1] & 0xF0) >> 4
    return -magnitude if last_zone == 0x0D else magnitude


def encode_zoned(data, offset, length, signed, value):
    for i in range(length):
        data[offset + i] = 0xF0
    pos = length - 1
    for ch in reversed(str(abs(value))):
        if pos < 0:
            break
        data[offset + pos] = 0xF0 | (ord(ch) - ord("0"))
        pos -= 1
    sign = (0x0D if value < 0 else 0x0C) if signed else 0x0F
    last_digit = data[offset + length - 1] & 0x0F
    data[offset + length - 1] = (sign << 4) | last_digit


def decode_binary(data, offset, length, signed):
    return int.from_bytes(bytes(data[offset:offset + length]), "big", signed=signed)


def encode_binary(data, offset, length, signed, value):
    data[offset:offset + length] = int(value).to_bytes(length, "big", signed=signed)


def decode_alphanumeric(data, offset, length):
    return bytes(data[offset:offset + length]).decode("latin-1")


def encode_alphanumeric(data, offset, length, value):
    raw = value.encode("latin-1")
    for i in range(length):
        data[offset + i] = raw[i] if i < len(raw) else 0x20

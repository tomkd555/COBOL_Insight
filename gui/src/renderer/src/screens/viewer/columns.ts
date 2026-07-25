/**
 * 固定形式の桁計算(React 非依存の純ロジック)。
 *
 * 固定形式の桁はバイトで数える(docs/02 §3.4・docs/04 §5「桁計算はDBCS混在に備え全面をバイト単位で
 * 行う」)。engine の固定形式ノーマライザは原本の文字集合でバイト長を求めて桁を決めるため
 * (FixedFormatNormalizer.byteLength)、表示側も同じ文字集合のバイト長で数える。文字数で数えると
 * DBCS を含む行で識別欄(73〜80桁)と本体(8〜72桁)の境界がずれる。
 *
 * 復号に用いる文字集合は readSourceText が返す2種だけである(それ以外は復号非対応で本文を持たない)。
 * Shift_JIS は ASCII と半角カタカナが1バイト・それ以外が2バイト、UTF-8 は TextEncoder のバイト長を
 * 用いる。
 */

/** 桁計算に用いる文字集合。 */
export type SourceCodepage = "Shift_JIS" | "UTF-8";

const ENCODER = new TextEncoder();

/** 半角カタカナ(JIS X 0201 片仮名)の範囲。Shift_JIS では1バイトで表す。 */
const HALFWIDTH_KATAKANA_FIRST = 0xff61;
const HALFWIDTH_KATAKANA_LAST = 0xff9f;

/** readSourceText が返すコードページ表示名を、桁計算の文字集合へ写す。判らない値は UTF-8 とする。 */
export function sourceCodepageOf(codepage: string): SourceCodepage {
  const normalized = codepage.trim().toUpperCase().replace(/[_\s]/g, "-");
  if (
    normalized === "SHIFT-JIS" ||
    normalized === "SJIS" ||
    normalized === "MS932" ||
    normalized === "CP932" ||
    normalized === "WINDOWS-31J"
  ) {
    return "Shift_JIS";
  }
  return "UTF-8";
}

/** 1文字(コードポイント1個)のバイト長。 */
function charBytes(char: string, codepage: SourceCodepage): number {
  if (codepage === "UTF-8") {
    return ENCODER.encode(char).length;
  }
  const code = char.codePointAt(0) ?? 0;
  if (code <= 0x7f) {
    return 1;
  }
  if (code >= HALFWIDTH_KATAKANA_FIRST && code <= HALFWIDTH_KATAKANA_LAST) {
    return 1;
  }
  return 2;
}

/** 文字列のバイト長。 */
export function byteLengthOf(text: string, codepage: SourceCodepage): number {
  let bytes = 0;
  for (const char of text) {
    bytes += charBytes(char, codepage);
  }
  return bytes;
}

/**
 * 先頭から byteCount バイトまでに収まる文字数(UTF-16 単位)。Monaco の桁と String の添字は
 * UTF-16 単位なので、サロゲートペアは2単位として数える。境界へ跨る文字は収まらないものとして
 * 除く(engine が識別欄を不可侵とし、文字を分割しないことに合わせる)。
 */
export function charIndexAfterBytes(
  line: string,
  byteCount: number,
  codepage: SourceCodepage,
): number {
  let bytes = 0;
  let index = 0;
  for (const char of line) {
    const size = charBytes(char, codepage);
    if (bytes + size > byteCount) {
      break;
    }
    bytes += size;
    index += char.length;
  }
  return index;
}

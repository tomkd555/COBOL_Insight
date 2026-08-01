import { describe, it, expect } from "vitest";
import { byteLengthOf, charIndexAfterBytes, sourceCodepageOf } from "./columns";

/** 日本語 10 文字と ASCII 59 文字からなる行。Shift_JIS では 79 バイト、UTF-8 では 89 バイトになる。 */
const MIXED_LINE = `${"A".repeat(59)}${"資".repeat(10)}`;

describe("sourceCodepageOf(復号に使った文字集合の判定)", () => {
  it("readSourceText が返す表示名を桁計算の文字集合へ写す", () => {
    expect(sourceCodepageOf("Shift_JIS")).toBe("Shift_JIS");
    expect(sourceCodepageOf("UTF-8")).toBe("UTF-8");
  });

  it("別名(windows-31j・MS932)も Shift_JIS として扱う", () => {
    expect(sourceCodepageOf("windows-31j")).toBe("Shift_JIS");
    expect(sourceCodepageOf("MS932")).toBe("Shift_JIS");
  });

  it("判らない表示名は UTF-8 として扱う", () => {
    expect(sourceCodepageOf("")).toBe("UTF-8");
    expect(sourceCodepageOf("不明")).toBe("UTF-8");
  });
});

describe("byteLengthOf(桁のもとになるバイト長)", () => {
  it("Shift_JIS では ASCII を1バイト・全角を2バイトで数える", () => {
    expect(byteLengthOf("ABC", "Shift_JIS")).toBe(3);
    expect(byteLengthOf("資産", "Shift_JIS")).toBe(4);
    expect(byteLengthOf(MIXED_LINE, "Shift_JIS")).toBe(79);
  });

  it("Shift_JIS では半角カタカナを1バイトで数える", () => {
    expect(byteLengthOf("ｱｲｳ", "Shift_JIS")).toBe(3);
  });

  it("UTF-8 では TextEncoder のバイト長で数える", () => {
    expect(byteLengthOf("資産", "UTF-8")).toBe(6);
    expect(byteLengthOf(MIXED_LINE, "UTF-8")).toBe(89);
  });

  it("空文字は 0 バイトである", () => {
    expect(byteLengthOf("", "Shift_JIS")).toBe(0);
    expect(byteLengthOf("", "UTF-8")).toBe(0);
  });
});

describe("charIndexAfterBytes(桁からの文字位置)", () => {
  it("ASCII だけの行では桁と文字位置が一致する", () => {
    const line = "A".repeat(80);
    expect(charIndexAfterBytes(line, 72, "Shift_JIS")).toBe(72);
    expect(charIndexAfterBytes(line, 7, "Shift_JIS")).toBe(7);
  });

  it("Shift_JIS の日本語混在行では 72 バイトに収まる文字数を返す", () => {
    // 59 バイト(ASCII)+ 全角 6 文字(12 バイト)= 71 バイト。7 文字目は 73 バイト目へ跨るため含めない。
    expect(charIndexAfterBytes(MIXED_LINE, 72, "Shift_JIS")).toBe(65);
  });

  it("UTF-8 の日本語混在行では 3 バイト換算で文字数を返す", () => {
    // 59 バイト(ASCII)+ 全角 4 文字(12 バイト)= 71 バイト。
    expect(charIndexAfterBytes(MIXED_LINE, 72, "UTF-8")).toBe(63);
  });

  it("行全体が指定バイト以内なら行の文字数を返す", () => {
    expect(charIndexAfterBytes("ABC", 72, "Shift_JIS")).toBe(3);
    expect(charIndexAfterBytes("", 72, "Shift_JIS")).toBe(0);
  });

  it("サロゲートペアは UTF-16 の 2 単位として数える(Monaco の桁と一致させる)", () => {
    // 𠀋(U+2000B)は Shift_JIS でも 2 バイト、UTF-16 では 2 単位である。
    expect(charIndexAfterBytes("𠀋𠀋", 2, "Shift_JIS")).toBe(2);
    expect(charIndexAfterBytes("𠀋𠀋", 3, "Shift_JIS")).toBe(2);
    expect(charIndexAfterBytes("𠀋𠀋", 4, "Shift_JIS")).toBe(4);
  });
});

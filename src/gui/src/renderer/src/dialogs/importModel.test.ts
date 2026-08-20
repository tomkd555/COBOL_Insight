import { describe, it, expect } from "vitest";
import { clipColumns, clipLine, columnRuler, displayWidth } from "./importModel";

describe("displayWidth(端末の表示桁)", () => {
  it("半角英数を1桁として数える", () => {
    expect(displayWidth("MOVE A TO B")).toBe(11);
  });

  it("全角文字を2桁として数える", () => {
    expect(displayWidth("受注番号")).toBe(8);
  });

  it("半角カタカナを1桁として数える", () => {
    expect(displayWidth("ｼﾞｭﾁｭｳ")).toBe(6);
  });

  it("タブを次の8桁境界までの空白として数える", () => {
    expect(displayWidth("\tA")).toBe(9);
    expect(displayWidth("AB\tC")).toBe(9);
  });
});

describe("clipLine(1行の桁取り)", () => {
  it("開始桁と終了桁(1起点・両端含む)で切り出す", () => {
    expect(clipLine("ABCDEFGH", 3, 5)).toBe("CDE");
  });

  it("行番号領域を持つ端末の表示から本文だけを取り出す", () => {
    expect(clipLine("000100 IDENTIFICATION DIVISION.", 8, 80)).toBe("IDENTIFICATION DIVISION.");
  });

  it("行が終了桁より短ければ、あるところまでを返す", () => {
    expect(clipLine("ABC", 2, 80)).toBe("BC");
  });

  it("開始桁が行の長さを超えれば空を返す", () => {
    expect(clipLine("ABC", 10, 80)).toBe("");
  });

  it("全角文字を2桁として数え、桁位置をずらさない", () => {
    // 「品名」が3〜6桁を占めるため、7桁目から始まる X を7桁目として取り出す。
    expect(clipLine("  品名 X", 7, 8)).toBe(" X");
  });

  it("境界にまたがる全角文字を、占める桁数分の空白へ置き換える", () => {
    expect(clipLine("AB漢CD", 1, 3)).toBe("AB ");
    expect(clipLine("AB漢CD", 4, 6)).toBe(" CD");
  });

  it("タブを桁位置まで空白へ展開してから切り出す", () => {
    // タブは9桁目まで送るため、A は9桁目に来る。
    expect(clipLine("\tA", 9, 9)).toBe("A");
    // 切り出した結果にタブを残さない(残すと表示の桁と保存の桁が食い違う)。
    expect(clipLine("000100\tPROGRAM-ID.", 1, 20)).toBe("000100  PROGRAM-ID.");
  });
});

describe("clipColumns(本文全体の桁取り)", () => {
  it("CRLF・CR・LF のいずれの改行でも行へ分ける", () => {
    expect(clipColumns("AAA\r\nBBB\rCCC\nDDD", 1, 80)).toEqual(["AAA", "BBB", "CCC", "DDD"]);
  });

  it("各行の末尾の空白を落とす", () => {
    expect(clipColumns("MOVE A TO B.    ", 1, 80)).toEqual(["MOVE A TO B."]);
  });

  it("先頭の空白は桁位置なので残す", () => {
    expect(clipColumns("       MOVE A TO B.", 1, 80)).toEqual(["       MOVE A TO B."]);
  });

  it("末尾の空行を落とす(端末の画面には余白の行が含まれる)", () => {
    expect(clipColumns("AAA\n\nBBB\n\n\n", 1, 80)).toEqual(["AAA", "", "BBB"]);
  });

  it("空の本文は行を返さない", () => {
    expect(clipColumns("", 1, 80)).toEqual([]);
    expect(clipColumns("   \n  \n", 1, 80)).toEqual([]);
  });
});

describe("columnRuler(桁定規)", () => {
  it("10桁ごとの位取りと1桁ごとの目盛を2行で返す", () => {
    const ruler = columnRuler(12);
    expect(ruler.tens).toBe("         1  ");
    expect(ruler.ones).toBe("123456789012");
  });
});

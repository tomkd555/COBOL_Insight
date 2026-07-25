import { describe, it, expect } from "vitest";
import {
  analysisStatus,
  buildAssetGroups,
  codepageLabel,
  directoryOf,
  displayType,
  effectiveEncoding,
  encodingSelectValue,
  matchesSearch,
  matchesType,
  previewCodepage,
  toCodepageOverrides,
  ENCODING_OPTIONS,
  MANUAL_ENCODING_OPTIONS,
} from "./assetView";
import { SAMPLE_INVENTORY } from "./fixtures";
import type { AssetInventoryItem } from "../../../../shared/engine-api";

const item = (over: Partial<AssetInventoryItem>): AssetInventoryItem => ({
  id: 1,
  path: "cobol/X.cbl",
  name: "X.cbl",
  type: "PROGRAM",
  codepage: "UTF-8",
  byteSize: 100,
  findingCount: 0,
  ...over,
});

describe("displayType(NODE.type → 表示種別)", () => {
  it.each([
    ["PROGRAM", "COBOL"],
    ["JCL", "JCL"],
    ["COPYBOOK", "コピー句"],
    ["BMS", "BMSマップ"],
    ["DATASET", "その他"],
    ["UNKNOWN", "その他"],
  ] as const)("%s を %s へ写す", (nodeType, expected) => {
    expect(displayType(nodeType)).toBe(expected);
  });
});

describe("matchesType(種別チップ)", () => {
  it("すべては全種別に一致する", () => {
    expect(matchesType("COBOL", "すべて")).toBe(true);
    expect(matchesType("その他", "すべて")).toBe(true);
  });
  it("BMS チップは BMSマップだけに一致する", () => {
    expect(matchesType("BMSマップ", "BMS")).toBe(true);
    expect(matchesType("COBOL", "BMS")).toBe(false);
  });
  it("その他チップはその他だけに一致する", () => {
    expect(matchesType("その他", "その他")).toBe(true);
    expect(matchesType("JCL", "その他")).toBe(false);
  });
  it("JCL/COBOL/コピー句は同名種別に一致する", () => {
    expect(matchesType("JCL", "JCL")).toBe(true);
    expect(matchesType("コピー句", "コピー句")).toBe(true);
    expect(matchesType("COBOL", "JCL")).toBe(false);
  });
});

describe("matchesSearch(名前フィルタ)", () => {
  it("空文字は全件一致する", () => {
    expect(matchesSearch("SYK001.cbl", "")).toBe(true);
  });
  it("大文字小文字を無視して部分一致する", () => {
    expect(matchesSearch("SYK001.cbl", "syk0")).toBe(true);
    expect(matchesSearch("SYK001.cbl", "cbl")).toBe(true);
    expect(matchesSearch("SYK001.cbl", "ZZZ")).toBe(false);
  });
});

describe("codepageLabel(engine の charset 名 → 利用者向け表記)", () => {
  it.each([
    ["windows-31j", "Shift_JIS"],
    ["x-IBM930", "EBCDIC CP930"],
    ["x-IBM939", "EBCDIC CP939"],
    ["UTF-8", "UTF-8"],
  ] as const)("%s を %s へ写す", (charset, expected) => {
    expect(codepageLabel(charset)).toBe(expected);
  });
});

describe("effectiveEncoding / encodingSelectValue", () => {
  it("手動指定を検出結果より優先する", () => {
    const it1 = item({ path: "cobol/X.cbl", codepage: "UTF-8" });
    expect(effectiveEncoding(it1, { "cobol/X.cbl": "手動: EBCDIC CP930" })).toBe("手動: EBCDIC CP930");
  });
  it("未指定は検出コードページの利用者向け表記、復号失敗(null)は未判定を返す", () => {
    expect(effectiveEncoding(item({ codepage: "UTF-8" }), {})).toBe("UTF-8");
    expect(effectiveEncoding(item({ codepage: "windows-31j" }), {})).toBe("Shift_JIS");
    expect(effectiveEncoding(item({ codepage: "x-IBM930" }), {})).toBe("EBCDIC CP930");
    expect(effectiveEncoding(item({ codepage: null }), {})).toBe("未判定");
  });
  it("選択欄の既定値は検出結果に対応する自動判定選択肢", () => {
    expect(encodingSelectValue(item({ codepage: "Shift_JIS" }), {})).toBe("自動判定: Shift_JIS");
    expect(encodingSelectValue(item({ codepage: "UTF-8" }), {})).toBe("自動判定: UTF-8");
  });
  it("engine が記録する SHIFT_JIS の charset 名(windows-31j)を Shift_JIS として扱う", () => {
    expect(encodingSelectValue(item({ codepage: "windows-31j" }), {})).toBe("自動判定: Shift_JIS");
    expect(previewCodepage(item({ codepage: "windows-31j" }), {})).toBe("windows-31j");
  });
  it("EBCDIC の推定結果は推定の選択肢を返す(自動判定 UTF-8 へ落とさない)", () => {
    expect(encodingSelectValue(item({ codepage: "x-IBM930" }), {})).toBe("推定: EBCDIC CP930");
    expect(encodingSelectValue(item({ codepage: "x-IBM939" }), {})).toBe("推定: EBCDIC CP939");
  });
  it("選択欄の値は手動指定があればそれを返す", () => {
    const it1 = item({ path: "cobol/X.cbl" });
    expect(encodingSelectValue(it1, { "cobol/X.cbl": "手動: UTF-8" })).toBe("手動: UTF-8");
  });
  it("選択肢は自動判定2件・EBCDIC 推定2件・手動4件の計8件で、すべて選択欄の値になり得る", () => {
    expect(ENCODING_OPTIONS).toHaveLength(8);
    expect(ENCODING_OPTIONS).toContain("推定: EBCDIC CP930");
    expect(ENCODING_OPTIONS).toContain("手動: EBCDIC CP930");
    expect(ENCODING_OPTIONS).toContain("手動: EBCDIC CP939");
    for (const codepage of ["UTF-8", "windows-31j", "x-IBM930", "x-IBM939"]) {
      expect(ENCODING_OPTIONS).toContain(encodingSelectValue(item({ codepage }), {}));
    }
  });
  it("手動指定の選択肢は全選択肢の部分集合である(設定の既定文字コードもこの語彙を用いる)", () => {
    expect(MANUAL_ENCODING_OPTIONS).toHaveLength(4);
    for (const option of MANUAL_ENCODING_OPTIONS) {
      expect(ENCODING_OPTIONS).toContain(option);
    }
  });
  it("検出に失敗した資産は設定の既定文字コードを選択欄の初期値にする", () => {
    expect(encodingSelectValue(item({ codepage: null }), {}, "手動: EBCDIC CP930")).toBe(
      "手動: EBCDIC CP930",
    );
    expect(encodingSelectValue(item({ codepage: null }), {}, "手動: Shift_JIS")).toBe("手動: Shift_JIS");
  });
  it("既定の文字コードは検出できた資産の選択欄を変えない", () => {
    expect(encodingSelectValue(item({ codepage: "windows-31j" }), {}, "手動: UTF-8")).toBe(
      "自動判定: Shift_JIS",
    );
  });
  it("資産ごとの手動指定は既定の文字コードより優先する", () => {
    const it1 = item({ path: "cobol/X.cbl", codepage: null });
    expect(encodingSelectValue(it1, { "cobol/X.cbl": "手動: UTF-8" }, "手動: Shift_JIS")).toBe(
      "手動: UTF-8",
    );
  });
});

describe("toCodepageOverrides(scan への反映)", () => {
  it("手動指定のみを charset コードへ変換し、自動判定は含めない", () => {
    const overrides = toCodepageOverrides({
      "cobol/A.cbl": "手動: EBCDIC CP930",
      "cobol/B.cbl": "自動判定: UTF-8",
      "cobol/C.cbl": "手動: Shift_JIS",
    });
    expect(overrides).toEqual({ "cobol/A.cbl": "CP930", "cobol/C.cbl": "Shift_JIS" });
  });
});

describe("previewCodepage(プレビュー復号のコードページ)", () => {
  it("手動指定があれば charset コードを返す", () => {
    expect(previewCodepage(item({}), { "cobol/X.cbl": "手動: EBCDIC CP930" })).toBe("CP930");
  });

  it("自動判定のままなら検出コードページを返す", () => {
    expect(previewCodepage(item({ codepage: "Shift_JIS" }), { "cobol/X.cbl": "自動判定: Shift_JIS" })).toBe(
      "Shift_JIS",
    );
    expect(previewCodepage(item({ codepage: "UTF-8" }), {})).toBe("UTF-8");
  });

  it("検出に失敗した資産は null を返す(復号非対応として扱う)", () => {
    expect(previewCodepage(item({ codepage: null }), {})).toBeNull();
  });

  it("検出に失敗した資産は設定の既定文字コードで復号する", () => {
    expect(previewCodepage(item({ codepage: null }), {}, "手動: Shift_JIS")).toBe("Shift_JIS");
    expect(previewCodepage(item({ codepage: null }), {}, "手動: EBCDIC CP930")).toBe("CP930");
  });

  it("既定の文字コードは検出できた資産の復号を変えない", () => {
    expect(previewCodepage(item({ codepage: "UTF-8" }), {}, "手動: Shift_JIS")).toBe("UTF-8");
  });
});

describe("analysisStatus(解析状態)", () => {
  it("running は解析中、empty は—", () => {
    expect(analysisStatus(item({}), "running").label).toBe("解析中…");
    expect(analysisStatus(item({}), "empty").label).toBe("—");
  });
  it("results で復号失敗(codepage=null)は✗ 復号失敗", () => {
    expect(analysisStatus(item({ codepage: null, findingCount: 1 }), "results")).toEqual({
      label: "✗ 復号失敗",
      tone: "error",
    });
  });
  it("results で復号は成功したが構文解析に失敗した資産は✗ 構文解析失敗(design:908 の PARSEF)", () => {
    expect(analysisStatus(item({ codepage: "windows-31j", findingCount: 1 }), "results")).toEqual({
      label: "✗ 構文解析失敗",
      tone: "error",
    });
  });
  it("results でその他種別は取込のみ", () => {
    expect(analysisStatus(item({ type: "DATASET" }), "results").label).toBe("取込のみ");
  });
  it("results で解析対象は✓ 解析済", () => {
    expect(analysisStatus(item({ type: "PROGRAM" }), "results")).toEqual({ label: "✓ 解析済", tone: "success" });
  });
});

describe("directoryOf", () => {
  it("パスのディレクトリ部を取り出す", () => {
    expect(directoryOf("cobol/SYK001.cbl")).toBe("cobol");
    expect(directoryOf("a/b/c.cpy")).toBe("a/b");
  });
  it("区切りが無ければ（ルート）", () => {
    expect(directoryOf("README.txt")).toBe("（ルート）");
  });
});

describe("buildAssetGroups(集約+絞り込み)", () => {
  const base = {
    search: "",
    type: "すべて" as const,
    encodingSel: {},
    mode: "results" as const,
    selectedPath: "",
    findingCounts: {},
  };

  it("ディレクトリ単位に集約し、初出順を保つ", () => {
    const groups = buildAssetGroups(SAMPLE_INVENTORY, base);
    expect(groups.map((g) => g.dir)).toEqual(["bms", "cobol", "copybook", "jcl"]);
    const cobol = groups.find((g) => g.dir === "cobol");
    expect(cobol?.count).toBe(3);
  });

  it("名前フィルタで行を絞り、空グループを除く", () => {
    const groups = buildAssetGroups(SAMPLE_INVENTORY, { ...base, search: "SYK00" });
    expect(groups.map((g) => g.dir)).toEqual(["cobol"]);
    expect(groups[0].rows.map((r) => r.name)).toEqual(["SYK001.cbl", "SYK002.cbl"]);
  });

  it("種別チップで絞る(コピー句)", () => {
    const groups = buildAssetGroups(SAMPLE_INVENTORY, { ...base, type: "コピー句" });
    expect(groups.map((g) => g.dir)).toEqual(["copybook"]);
  });

  it("その他チップは NODE 未登録(復号失敗)の資産を拾う", () => {
    const groups = buildAssetGroups(SAMPLE_INVENTORY, { ...base, type: "その他" });
    expect(groups.map((g) => g.dir)).toEqual(["cobol"]);
    expect(groups[0].rows.map((r) => r.name)).toEqual(["SYKENC1.cbl"]);
    expect(groups[0].rows[0].status.label).toBe("✗ 復号失敗");
  });

  it("選択パスの行に selected を立てる", () => {
    const groups = buildAssetGroups(SAMPLE_INVENTORY, { ...base, selectedPath: "cobol/SYK001.cbl" });
    const selected = groups.flatMap((g) => g.rows).filter((r) => r.selected);
    expect(selected.map((r) => r.name)).toEqual(["SYK001.cbl"]);
  });

  it("行は種別・文字コード・状態を持ち、指摘件数は lint の件数を採る", () => {
    const groups = buildAssetGroups(SAMPLE_INVENTORY, {
      ...base,
      findingCounts: { "cobol/SYK001.cbl": 4 },
    });
    const row = groups.flatMap((g) => g.rows).find((r) => r.name === "SYK001.cbl");
    expect(row).toMatchObject({ type: "COBOL", encoding: "Shift_JIS", findingCount: 4 });
    expect(row?.status.label).toBe("✓ 解析済");
  });

  it("lint 指摘が無いファイルの指摘件数は 0 になる", () => {
    const groups = buildAssetGroups(SAMPLE_INVENTORY, {
      ...base,
      findingCounts: { "cobol/SYK001.cbl": 4 },
    });
    const row = groups.flatMap((g) => g.rows).find((r) => r.name === "SYK002.cbl");
    expect(row?.findingCount).toBe(0);
  });

  it("scan 由来の失敗件数を持つ資産は構文解析失敗として示す(lint の指摘件数とは無関係)", () => {
    const groups = buildAssetGroups(SAMPLE_INVENTORY, base);
    const row = groups.flatMap((g) => g.rows).find((r) => r.name === "SYK002.cbl");
    expect(row?.status).toEqual({ label: "✗ 構文解析失敗", tone: "error" });
  });
});

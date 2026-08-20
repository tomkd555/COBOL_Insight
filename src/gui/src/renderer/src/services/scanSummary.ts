/**
 * scan のサマリ JSON から、走査の付帯情報を取り出す。engine は内容から種別を逆算する単一の走査を
 * 行い、種別を判定できなかったもの・読み取れなかったもの・拡張子と内容が食い違い内容を優先した
 * ものを全件返す。画面はこれを取りこぼしの案内に用いる。
 */

/** engine が資産の種別判定に用いる種別名(拡張子ではなく判定結果)。 */
export type DiscoveredAssetKind = "COBOL" | "COPYBOOK" | "JCL" | "BMS";

/** 拡張子と内容の判定が食い違い、内容を優先して取り込んだ1件。 */
export interface KindMismatch {
  readonly path: string;
  readonly byExtension: DiscoveredAssetKind;
  readonly byContent: DiscoveredAssetKind;
}

/** 走査の付帯情報。 */
export interface ScanDiscovery {
  /** 種別を判定できず対象外とした相対パス(全件)。 */
  readonly undecided: readonly string[];
  /** 拡張子と内容が食い違い、内容を優先して取り込んだもの(全件)。 */
  readonly mismatches: readonly KindMismatch[];
  /** 走査の上限に達して打ち切ったか。 */
  readonly truncated: boolean;
  /** 読み取れず対象外とした相対パス(全件)。 */
  readonly unreadable: readonly string[];
}

const DISCOVERED_ASSET_KINDS: readonly DiscoveredAssetKind[] = ["COBOL", "COPYBOOK", "JCL", "BMS"];

function isDiscoveredAssetKind(value: unknown): value is DiscoveredAssetKind {
  return typeof value === "string" && (DISCOVERED_ASSET_KINDS as readonly string[]).includes(value);
}

/**
 * engine が項目を返さない場合(古い engine を起動した場合など)は「取りこぼし無し」として扱い、
 * 警告を出さない側へ倒す。型の合わない値は個別に読み飛ばし、他の正しい値まで握り潰さない。
 */
export function readScanDiscovery(summary: Record<string, unknown> | null): ScanDiscovery {
  return {
    undecided: stringsOf(summary?.["undecided"]),
    mismatches: mismatchesOf(summary?.["mismatches"]),
    truncated: summary?.["truncated"] === true,
    unreadable: stringsOf(summary?.["unreadable"]),
  };
}

function stringsOf(value: unknown): readonly string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === "string")
    : [];
}

function mismatchesOf(value: unknown): readonly KindMismatch[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const result: KindMismatch[] = [];
  for (const entry of value) {
    if (entry === null || typeof entry !== "object") {
      continue;
    }
    const record = entry as Record<string, unknown>;
    const path = record["path"];
    const byExtension = record["byExtension"];
    const byContent = record["byContent"];
    if (
      typeof path === "string" &&
      isDiscoveredAssetKind(byExtension) &&
      isDiscoveredAssetKind(byContent)
    ) {
      result.push({ path, byExtension, byContent });
    }
  }
  return result;
}

/** 走査で判定した種別名から、利用者向けの表示名を引く。 */
export const DISCOVERED_ASSET_KIND_LABELS: Readonly<Record<DiscoveredAssetKind, string>> = {
  COBOL: "COBOL 本体",
  COPYBOOK: "コピー句",
  JCL: "JCL",
  BMS: "BMS",
};

/**
 * 走査の警告1件。text が概要、details は該当ファイルの一覧(全件)である。details が空でなければ
 * 画面側は畳んで示す(畳むのは表示の整理であって、件数を切り捨てるわけではない)。
 */
export interface ScanNoticeSection {
  readonly text: string;
  readonly details: readonly string[];
}

const NO_DETAILS: readonly string[] = [];

/**
 * engine が候補にする既知拡張子。走査の可否はソースの内容で決まり、この一覧は案内文の材料に限る。
 */
const RECOGNIZED_EXTENSIONS = ".cbl / .cob / .cobol / .cpy / .copy / .jcl / .bms";

/**
 * 走査の警告。解析は成功したが取り込めていない・解釈を変えたものがある場合を扱う。0 件は異常
 * 終了ではないため、終了コードもモードも成功のまま警告だけを出す。報告の優先順位は
 * 「黙って落としたもの ＞ 黙って解釈を変えたもの ＞ 打ち切り」である。
 */
export function scanNotices(
  discovery: ScanDiscovery | null,
  assetCount: number,
): readonly ScanNoticeSection[] {
  if (discovery === null) {
    return [];
  }
  const sections: ScanNoticeSection[] = [];
  if (assetCount === 0) {
    sections.push({
      text:
        "対象の資産が 1 件も見つかりませんでした。選んだフォルダに COBOL・コピー句・JCL・BMS の" +
        `ソースがあるか確かめてください（対応する拡張子の例: ${RECOGNIZED_EXTENSIONS}）。` +
        "拡張子が無くても、内容から種別を判定します。",
      details: NO_DETAILS,
    });
  }
  if (discovery.undecided.length > 0) {
    sections.push({
      text:
        `${discovery.undecided.length} 件は種別を判定できなかったため対象外にしました。COBOL 本体なら ` +
        "IDENTIFICATION DIVISION、コピー句ならレベル番号で始まる項目定義、JCL なら // で始まる行、" +
        "BMS なら DFHMSD を含むか確かめてください。",
      details: discovery.undecided,
    });
  }
  if (discovery.unreadable.length > 0) {
    sections.push({
      text: `${discovery.unreadable.length} 件は読み取れなかったため対象外にしました。`,
      details: discovery.unreadable,
    });
  }
  if (discovery.mismatches.length > 0) {
    sections.push({
      text: `${discovery.mismatches.length} 件は拡張子と内容が食い違ったため、内容を優先して取り込みました。`,
      details: discovery.mismatches.map(
        (mismatch) => `${mismatch.path} → ${DISCOVERED_ASSET_KIND_LABELS[mismatch.byContent]}`,
      ),
    });
  }
  if (discovery.truncated) {
    sections.push({
      text: "走査の上限に達したため、一部のファイルを取り込んでいません。",
      details: NO_DETAILS,
    });
  }
  return sections;
}

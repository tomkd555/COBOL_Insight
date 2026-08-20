/**
 * 画面の設定の保存形式。main(ファイルの読み書き)と renderer(復元と保存)の双方が使うため
 * shared へ置く。
 *
 * 保存するのは設定画面が持つ3項目に限る。資産フォルダ・選択中の資産・絞り込みの語のように、
 * 1回の作業の間だけ意味を持つ値は保存しない(起動のたびに空の状態から始める設計を崩さない)。
 *
 * 無効にしたルールはここに含めない。engine が読む rules-config.json を唯一の置き場所とし、
 * ファイルからも画面からも同じ設定を扱えるようにするためである。旧版が書いた disabledRules だけは、
 * 移行が rules-config.json を書き終えるまで消さずに持ち越す。
 *
 * ここでの正規化は型を整えるだけで、重大度や文字コードの語彙が正しいかは見ない。語彙の一覧は
 * renderer 側が持つため、突き合わせは復元時(settingsStore の RESTORE)が行う。
 */

/** 保存ファイルの版数。形式を変えるときに上げる。 */
export const APP_SETTINGS_VERSION = 1;

export interface AppSettings {
  /** 表示する重大度のしきい値(high/medium/low/warning)。 */
  readonly severityThreshold: string;
  /** 既定の文字コード。 */
  readonly defaultEncoding: string;
  /** コピー句探索パス。並びのまま --copybook-path へ渡る。 */
  readonly copybookPaths: readonly string[];
  /**
   * 分割ペインの寸法(画素)。キーは renderer の SplitPaneId だが、その型は renderer 側にあり
   * ここからは参照できないため文字列で持つ。知らないキーを捨てる突き合わせは復元時に行う。
   */
  readonly paneSizes: Readonly<Record<string, number>>;
  /**
   * 旧版が置いていた無効ルールの控え。画面はこの値を作らず、移行が rules-config.json を書き終える
   * まで settings.json に残っているものを持ち越すためだけに持つ(移行が済めば消える)。
   */
  readonly disabledRules?: readonly string[];
}

/** 保存ファイルの中身。 */
export interface AppSettingsFile {
  readonly version: number;
  readonly settings: AppSettings;
}

/** 何も保存されていないときの値。空を表し、復元時は画面の初期値がそのまま残る。 */
export function emptyAppSettings(): AppSettings {
  return {
    severityThreshold: "",
    defaultEncoding: "",
    copybookPaths: [],
    paneSizes: {},
  };
}

/** 読み込んだ値を型の揃った形へ整える。欠けた欄と型の合わない欄は空として扱う。 */
export function normalizeAppSettings(value: unknown): AppSettings {
  const source = asObject(value);
  const settings = asObject(source["settings"] ?? source);
  const normalized: AppSettings = {
    severityThreshold: asString(settings["severityThreshold"]),
    defaultEncoding: asString(settings["defaultEncoding"]),
    copybookPaths: asStringArray(settings["copybookPaths"]),
    paneSizes: asNumberRecord(settings["paneSizes"]),
  };
  // 旧版の無効ルールは、移行が済むまで読み書きのどちらでも落とさない。
  return Array.isArray(settings["disabledRules"])
    ? { ...normalized, disabledRules: asStringArray(settings["disabledRules"]) }
    : normalized;
}

function asObject(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function asString(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function asStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((element) => typeof element === "string") : [];
}

/** 数値の欄だけを残す。数として扱えない値(文字列・NaN・無限大)は落とす。 */
function asNumberRecord(value: unknown): Record<string, number> {
  const source = asObject(value);
  const result: Record<string, number> = {};
  for (const [key, element] of Object.entries(source)) {
    if (typeof element === "number" && Number.isFinite(element)) {
      result[key] = element;
    }
  }
  return result;
}

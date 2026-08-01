/**
 * 画面の設定の保存形式。main(ファイルの読み書き)と renderer(復元と保存)の双方が使うため
 * shared へ置く。
 *
 * 保存するのは設定画面が持つ4項目に限る。資産フォルダ・選択中の資産・絞り込みの語のように、
 * 1回の作業の間だけ意味を持つ値は保存しない(起動のたびに空の状態から始める設計を崩さない)。
 *
 * ここでの正規化は型を整えるだけで、重大度や文字コードの語彙が正しいかは見ない。語彙の一覧は
 * renderer 側が持つため、突き合わせは復元時(appReducer の RESTORE_SETTINGS)が行う。
 */

/** 保存ファイルの版数。形式を変えるときに上げる。 */
export const APP_SETTINGS_VERSION = 1;

export interface AppSettings {
  /** 無効化したルール ID。engine の --disable-rule へ渡す。 */
  readonly disabledRules: readonly string[];
  /** 表示する重大度のしきい値(high/medium/low/warning)。 */
  readonly severityThreshold: string;
  /** 既定の文字コード。 */
  readonly defaultEncoding: string;
  /** コピー句探索パス。並びのまま --copybook-path へ渡る。 */
  readonly copybookPaths: readonly string[];
}

/** 保存ファイルの中身。 */
export interface AppSettingsFile {
  readonly version: number;
  readonly settings: AppSettings;
}

/** 何も保存されていないときの値。空を表し、復元時は画面の初期値がそのまま残る。 */
export function emptyAppSettings(): AppSettings {
  return {
    disabledRules: [],
    severityThreshold: "",
    defaultEncoding: "",
    copybookPaths: [],
  };
}

/** 読み込んだ値を型の揃った形へ整える。欠けた欄と型の合わない欄は空として扱う。 */
export function normalizeAppSettings(value: unknown): AppSettings {
  const source = asObject(value);
  const settings = asObject(source["settings"] ?? source);
  return {
    disabledRules: asStringArray(settings["disabledRules"]),
    severityThreshold: asString(settings["severityThreshold"]),
    defaultEncoding: asString(settings["defaultEncoding"]),
    copybookPaths: asStringArray(settings["copybookPaths"]),
  };
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

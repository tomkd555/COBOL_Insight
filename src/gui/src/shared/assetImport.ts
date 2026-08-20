/**
 * 端末取込が資産フォルダへ書き出すときの、保存先と拡張子の決まり。
 *
 * engine の走査はソースの内容から種別を逆算するため、保存先のフォルダ名は解析の可否を決めない。
 * 保存先は利用者が相対パスで指定し、既定は資産フォルダの直下である。拡張子を補うのはコピー句
 * だけとする。コピー句は COPY 文の名前から探すため、engine の探索が .cpy を手掛かりにする。
 *
 * 取込先の決定は書き込む main と、保存先を示す renderer の双方が要するため、共有層へ置く。
 */

/** 取り込む資産の種別。engine の SourceKind に対応する。 */
export type ImportAssetKind = "cobol" | "copybook" | "jcl" | "bms";

export interface AssetKindSpec {
  readonly kind: ImportAssetKind;
  /** 画面に出す種別名。 */
  readonly label: string;
}

/** 種別の一覧。画面の選択肢の並びもこの順に従う。 */
export const ASSET_KIND_SPECS: readonly AssetKindSpec[] = [
  { kind: "cobol", label: "COBOL 本体" },
  { kind: "copybook", label: "コピー句" },
  { kind: "jcl", label: "JCL" },
  { kind: "bms", label: "BMS" },
];

export function assetKindSpec(kind: ImportAssetKind): AssetKindSpec {
  const spec = ASSET_KIND_SPECS.find((candidate) => candidate.kind === kind);
  if (spec === undefined) {
    throw new Error(`未知の資産種別: ${kind}`);
  }
  return spec;
}

/** コピー句へ補う拡張子。engine の COPY 文の探索がこの拡張子を手掛かりにする。 */
export const COPYBOOK_EXTENSION = ".cpy";

/** ファイル名に使えない文字。パス区切り、Windows が予約する記号、制御文字を拒む。 */
const FORBIDDEN_IN_NAME = /[\\/:*?"<>|]/;

/** 保存先の相対パスに使えない文字。パス区切りは許すが、予約記号は拒む。 */
const FORBIDDEN_IN_DIR = /[:*?"<>|]/;

/** 表示できない制御文字を含むか。端末からの複写に紛れ込むことがある。 */
function hasControlChar(text: string): boolean {
  return Array.from(text).some((char) => (char.codePointAt(0) ?? 0) < 0x20);
}

/**
 * 保存先フォルダの相対パスを正規化する。空文字は資産フォルダの直下を表す。区切りを / へそろえ、
 * 上位へ抜ける段(..)・絶対パス・予約記号・制御文字を含むものは null を返す。
 */
export function normalizeImportDir(destDir: string): string | null {
  const trimmed = destDir.trim().replace(/\\/g, "/").replace(/^\/+|\/+$/g, "");
  if (trimmed === "") {
    return "";
  }
  if (FORBIDDEN_IN_DIR.test(trimmed) || hasControlChar(trimmed)) {
    return null;
  }
  const segments = trimmed.split("/").filter((segment) => segment !== "" && segment !== ".");
  if (segments.length === 0) {
    return "";
  }
  if (segments.some((segment) => segment === ".." || /[. ]$/.test(segment))) {
    return null;
  }
  return segments.join("/");
}

/**
 * 取込先の相対パス(例: SYK001.cbl、cobol/SYK001.cbl)。名前が空、パス区切り・予約記号・制御文字を
 * 含む、点で始まる、空白または点で終わるときは null を返す。コピー句は .cpy で終わっていなければ補う。
 * 点で始まる名前を拒むのは、拡張子だけを入れたときに基底名の無い隠しファイルになるためである。
 */
export function importRelPath(
  kind: ImportAssetKind,
  destDir: string,
  fileName: string,
): string | null {
  const dir = normalizeImportDir(destDir);
  if (dir === null) {
    return null;
  }
  const name = fileName.trim();
  if (
    name === "" ||
    FORBIDDEN_IN_NAME.test(name) ||
    hasControlChar(name) ||
    name.startsWith(".") ||
    /[. ]$/.test(name)
  ) {
    return null;
  }
  const withExtension =
    kind === "copybook" && !name.toLowerCase().endsWith(COPYBOOK_EXTENSION)
      ? name + COPYBOOK_EXTENSION
      : name;
  return dir === "" ? withExtension : `${dir}/${withExtension}`;
}

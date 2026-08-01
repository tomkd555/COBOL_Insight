/**
 * 端末取込が資産フォルダへ書き出すときの、保存先フォルダ名と拡張子の既定値。engine の走査は
 * ソースの内容から種別を逆算するため、この対応表は走査の規約ではない。ここでの取り決めは
 * 「端末取込で保存したファイルをどこへ・どの拡張子で置くか」という GUI 側の既定に閉じており、
 * 解析の可否は一切決めない。取込先の決定は書き込む main と、保存先を示す renderer の双方が
 * 要するため、共有層へ置く。
 */

/** 取り込む資産の種別。engine の SourceKind に対応する。 */
export type ImportAssetKind = "cobol" | "copybook" | "jcl" | "bms";

export interface AssetKindSpec {
  readonly kind: ImportAssetKind;
  /** 画面に出す種別名。 */
  readonly label: string;
  /** 資産フォルダ直下の保存先フォルダ名。 */
  readonly folder: string;
  /** 走査が拾う拡張子(小文字)。 */
  readonly extension: string;
}

/** 種別の一覧。画面の選択肢の並びもこの順に従う。 */
export const ASSET_KIND_SPECS: readonly AssetKindSpec[] = [
  { kind: "cobol", label: "COBOL 本体", folder: "cobol", extension: ".cbl" },
  { kind: "copybook", label: "コピー句", folder: "copy", extension: ".cpy" },
  { kind: "jcl", label: "JCL", folder: "jcl", extension: ".jcl" },
  { kind: "bms", label: "BMS", folder: "bms", extension: ".bms" },
];

export function assetKindSpec(kind: ImportAssetKind): AssetKindSpec {
  const spec = ASSET_KIND_SPECS.find((candidate) => candidate.kind === kind);
  if (spec === undefined) {
    throw new Error(`未知の資産種別: ${kind}`);
  }
  return spec;
}

/** ファイル名に使えない文字。パス区切り、Windows が予約する記号、制御文字を拒む。 */
const FORBIDDEN_IN_NAME = /[\\/:*?"<>|]/;

/** 表示できない制御文字を含むか。端末からの複写に紛れ込むことがある。 */
function hasControlChar(name: string): boolean {
  return Array.from(name).some((char) => (char.codePointAt(0) ?? 0) < 0x20);
}

/**
 * 取込先の相対パス(例: cobol/SYK001.cbl)。名前が空、パス区切り・予約記号・制御文字を含む、
 * 点で始まる、空白または点で終わるときは null を返す。種別の拡張子で終わっていなければ補う。
 * 点で始まる名前を拒むのは、拡張子だけを入れたときに基底名の無い隠しファイルになるためである。
 */
export function importRelPath(kind: ImportAssetKind, fileName: string): string | null {
  const spec = assetKindSpec(kind);
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
  const withExtension = name.toLowerCase().endsWith(spec.extension) ? name : name + spec.extension;
  return `${spec.folder}/${withExtension}`;
}

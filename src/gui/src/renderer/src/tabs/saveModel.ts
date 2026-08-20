/**
 * 書き戻しの結果の解釈(React 非依存の純ロジック)。engine の save が返した要約から、
 * 未保存の印を外してよいか・利用者へ何を伝えるか・どの行に誤りを示すかを導く。
 *
 * 再パース検証は保存のたびに必ず走るが、コピー句・JCL・BMS は単体で構文解析できず、書き戻しが
 * 成功しても誤りが必ず返る。誤りを示すのは COBOL 本体だけとし、他の種別では終了コード 1 を
 * 「書き戻し済み」として扱う(engine の側は書き戻しを取り消していない)。
 */

import type { SarifFinding, SaveResult } from "../../../shared/engine-api";
import {
  LINE_END_COLUMN,
  MARKER_SEVERITY_ERROR,
  type EditorMarker,
} from "../screens/viewer/viewerModel";

/**
 * 再パース検証の誤りに使うルール ID。engine が構文解析の失敗へ用いる ID と同じものであり、
 * ルール一覧の引きが「構文解析失敗・重大度 高」を返す。
 */
export const REPARSE_RULE_ID = "parse-failure";

/** 書き戻せなかったのに理由が返らなかったときの文言。 */
const UNKNOWN_REASON = "解析エンジンが理由を返しませんでした。";

/** 再パース検証の誤りを示す資産か。 */
export function showsReparseErrors(assetType: string): boolean {
  return assetType === "PROGRAM";
}

/** 書き戻しの結末。保存できたか、原本が変わらなかったかで分ける。 */
export type SaveOutcome =
  | {
      readonly kind: "saved";
      readonly message: string;
      /** 再パース検証の誤り。示さない種別では常に空である。 */
      readonly diagnostics: readonly SarifFinding[];
    }
  | { readonly kind: "failed"; readonly message: string };

/**
 * save の要約を結末へ写す。終了コード 2 は原本が変わっていない(未保存のまま)ことを表す。
 *
 * @param path 資産の相対パス。指摘の表と本文の面が同じ綴りで引くため、engine が返した絶対パスは使わない。
 * @param assetType 資産の種別(NODE.type)。
 */
export function saveOutcomeOf(
  path: string,
  assetType: string,
  result: SaveResult,
): SaveOutcome {
  if (result.exitCode === 2) {
    return {
      kind: "failed",
      message: `${path} を保存できませんでした。${result.error === "" ? UNKNOWN_REASON : result.error} 文字コードの指定とコピー句の探索パスを確かめて、もう一度保存してください。`,
    };
  }
  if (!showsReparseErrors(assetType) || result.reparseErrors.length === 0) {
    return { kind: "saved", message: `${path} を保存しました。`, diagnostics: [] };
  }
  return {
    kind: "saved",
    message: `${path} を保存しました。保存時の検証で ${result.reparseErrors.length} 件の誤りが見つかりました。指摘の一覧で内容を確かめられます。`,
    diagnostics: result.reparseErrors.map((error) => ({
      ruleId: REPARSE_RULE_ID,
      level: "error",
      message: error.message,
      file: path,
      startLine: error.line,
      startColumn: 1,
    })),
  };
}

/** 再パース検証の誤りを、行全体を指すマーカーへ写す。 */
export function reparseMarkers(diagnostics: readonly SarifFinding[]): EditorMarker[] {
  return diagnostics.map((diagnostic) => ({
    startLineNumber: diagnostic.startLine,
    startColumn: 1,
    endLineNumber: diagnostic.startLine,
    endColumn: LINE_END_COLUMN,
    message: diagnostic.message,
    severity: MARKER_SEVERITY_ERROR,
  }));
}

import type {
  EngineResult,
  SaveReparseError,
  SaveRequest,
  SaveResult,
  SaveSourceRequest,
} from "../../shared/engine-api";
import { resolveSourceFile, type RealPathResolver } from "./sourceText";

/**
 * 画面で編集した本文の書き戻し。原本のコードページを保った符号化は Node では作れない
 * (Shift_JIS・EBCDIC の encoder が無い)ため、編集後の全文を UTF-8 の一時ファイルへ落とし、
 * engine の save サブコマンドへ引き渡す。
 *
 * 書き先は利用者が画面で開いたファイルであり、資産フォルダ配下に限る境界検査を読取と同じ関門
 * ({@link resolveSourceFile})で行う。一時ファイルは保存1回ごとに別名を作り、結果にかかわらず
 * 必ず片づける。名前を固定すると、保存が重なったときに後の保存が前の本文を上書きし、別の資産の
 * 本文を書き戻しうる。
 */

/** 書き戻しに使う fs の束ね。実体パスの解決に加え、一時ファイルの作成と削除を要する。 */
export interface SaveFileSystem extends RealPathResolver {
  /** UTF-8 のテキストファイルとして書く。 */
  writeText(absPath: string, text: string): Promise<void>;
  /** ファイルを消す。存在しなくても例外にしない。 */
  remove(absPath: string): Promise<void>;
}

export interface SaveDeps {
  fs: SaveFileSystem;
  /** 編集後の全文を置く一時ファイルの位置を1つ決める(成果物ディレクトリ配下)。保存ごとに呼ぶ。 */
  tempFile(): string;
  /** 走査時に記録したコードページを engine が引くプロジェクトファイル。 */
  dbPath: string;
  /** engine の save サブコマンドを1回起動する。 */
  run(request: SaveRequest): Promise<EngineResult>;
}

/**
 * save の要約 JSON を {@link SaveResult} へ整える。要約が無いのは engine が結果を書く前に落ちた
 * ときであり、書き戻せたかどうかを画面が判断できないため例外にする。
 */
export function parseSaveSummary(
  summary: Record<string, unknown> | null,
  exitCode: number,
): SaveResult {
  if (summary === null) {
    throw new Error(
      "解析エンジンが保存の結果を返さなかったため、書き戻せたかを確認できません。もう一度保存してください。",
    );
  }
  return {
    written: summary["written"] === true,
    path: asText(summary["path"]),
    changedLineFrom: asInt(summary["changedLineFrom"]),
    changedLineTo: asInt(summary["changedLineTo"]),
    reparseErrors: asReparseErrors(summary["reparseErrors"]),
    error: asText(summary["error"]),
    // 要約の exitCode を優先し、無いときだけプロセスの終了コードを使う。
    exitCode: typeof summary["exitCode"] === "number" ? summary["exitCode"] : exitCode,
  };
}

/** 資産フォルダ配下の原本へ、画面で編集した本文を書き戻す。 */
export async function saveSource(
  deps: SaveDeps,
  request: SaveSourceRequest,
): Promise<SaveResult> {
  const target = await resolveSourceFile(deps.fs, request.inputDir, request.path);
  const tempFile = deps.tempFile();
  await deps.fs.writeText(tempFile, request.editedText);
  try {
    const result = await deps.run({
      file: target,
      editedFile: tempFile,
      codepage: request.codepage,
      copybookPaths: request.copybookPaths,
      db: deps.dbPath,
    });
    return parseSaveSummary(result.summary, result.exitCode);
  } finally {
    // 後始末の失敗で保存の成否を覆さない。
    await deps.fs.remove(tempFile).catch(() => undefined);
  }
}

function asText(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function asInt(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

function asReparseErrors(value: unknown): SaveReparseError[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const errors: SaveReparseError[] = [];
  for (const element of value) {
    if (element === null || typeof element !== "object") {
      continue;
    }
    const record = element as Record<string, unknown>;
    errors.push({ line: asInt(record["line"]), message: asText(record["message"]) });
  }
  return errors;
}

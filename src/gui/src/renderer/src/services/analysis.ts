import type { AssetInventoryItem, SarifFinding } from "../../../shared/engine-api";
import type { ArtifactState } from "../state/projectStore";
import { readScanDiscovery, type ScanDiscovery } from "./scanSummary";

/**
 * 解析の起動そのもの。scan → lint → sql-lint を順に走らせ、成果物を読んで呼び手へ渡す。
 *
 * 画面から engine の起動を切り離す。どの画面から解析を始めても手順・出力先・失敗の扱いは1つで
 * あり、資産一覧の画面だけが解析の起点だった構造をここへ移した。
 *
 * 段の結果は済んだ端から呼び手へ渡す。3段そろうまで待つと、走査の終わった資産一覧が指摘の検出を
 * 待つ間ずっと表示されない。段ごとの失敗は空の結果へ潰さず、失敗としてそのまま伝える
 * (「指摘0件」と「検出できなかった」を利用者が取り違えないため)。
 */

/** 解析の段。走査・指摘の検出・SQL指摘の検出の3段である。 */
export type AnalysisStage = "scan" | "lint" | "sqlLint";

export interface AnalysisRequest {
  /** 資産フォルダ。 */
  inputDir: string;
  copybookPaths: string[];
  /** ファイル単位のコードページ手動指定。 */
  codepageOverrides: Record<string, string>;
}

/** 段の開始と、段ごとの結果を受け取る呼び手の口。 */
export interface AnalysisHandlers {
  /** 段を始める直前に呼ぶ。 */
  onStage(stage: AnalysisStage): void;
  onInventory(
    result: ArtifactState<AssetInventoryItem>,
    dbPath: string | null,
    discovery: ScanDiscovery | null,
  ): void;
  onFindings(result: ArtifactState<SarifFinding>): void;
  onSqlAdvice(result: ArtifactState<SarifFinding>): void;
}

/** 例外・非 Error 値から表示用の文言を取り出す。 */
export function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * 解析を1回走らせる。戻り値は「どこかの段でしくじったか」であり、engine の非ゼロ終了(構文解析に
 * 失敗した資産がある状態)も含む。
 */
export async function runAnalysis(
  request: AnalysisRequest,
  handlers: AnalysisHandlers,
): Promise<boolean> {
  const paths = await window.cobolInsight.getOutputPaths();

  handlers.onStage("scan");
  let scanFailed: boolean;
  try {
    const result = await window.cobolInsight.runScan({
      inputDir: request.inputDir,
      db: paths.db,
      copyExpansion: paths.copyExpansion,
      copybookPaths: request.copybookPaths,
      codepageOverrides: request.codepageOverrides,
    });
    const db = result.outputs.db;
    if (db === undefined) {
      // 保存先が分からなければ資産一覧を読めない。0 件として黙って通すと、解析できたのか
      // 対象が無いのかを利用者が区別できなくなる。
      throw new Error("解析結果の保存先を解析エンジンから受け取れませんでした。");
    }
    const items = await window.cobolInsight.readAssetInventory(db);
    handlers.onInventory({ status: "ready", items }, db, readScanDiscovery(result.summary));
    // 非ゼロ終了は構文解析の失敗を含む部分的成功であり、一覧は利用できる。
    scanFailed = result.exitCode !== 0;
  } catch (error) {
    handlers.onInventory({ status: "error", message: messageOf(error) }, null, null);
    scanFailed = true;
  }

  handlers.onStage("lint");
  let lintFailed: boolean;
  try {
    const result = await window.cobolInsight.runLint({
      inputDir: request.inputDir,
      sarifFile: paths.lintSarif,
      copybookPaths: request.copybookPaths,
      ruleConfigFile: paths.ruleConfig,
    });
    handlers.onFindings({ status: "ready", items: await readFindings(result.outputs.sarif) });
    lintFailed = false;
  } catch (error) {
    handlers.onFindings({ status: "error", message: messageOf(error) });
    lintFailed = true;
  }

  handlers.onStage("sqlLint");
  let sqlFailed: boolean;
  try {
    const result = await window.cobolInsight.runSqlLint({
      inputDir: request.inputDir,
      sarifFile: paths.sqlAdviseSarif,
      copybookPaths: request.copybookPaths,
      // S001〜S006 も無効にできるため、sql-lint へも同じ設定ファイルを渡す。
      ruleConfigFile: paths.ruleConfig,
    });
    handlers.onSqlAdvice({ status: "ready", items: await readFindings(result.outputs.sarif) });
    sqlFailed = false;
  } catch (error) {
    handlers.onSqlAdvice({ status: "error", message: messageOf(error) });
    sqlFailed = true;
  }

  return scanFailed || lintFailed || sqlFailed;
}

/** 書かれた SARIF を読む。出力先が返らなければ検出結果そのものが無い。 */
async function readFindings(sarifPath: string | undefined): Promise<SarifFinding[]> {
  if (sarifPath === undefined) {
    throw new Error("検出結果の出力先を解析エンジンから受け取れませんでした。");
  }
  return window.cobolInsight.readSarif(sarifPath);
}

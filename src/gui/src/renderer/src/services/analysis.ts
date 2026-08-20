import type { AssetInventoryItem, EngineResult, SarifFinding } from "../../../shared/engine-api";
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
 *
 * 取り消しは段の切れ目で見る。main は実行中の子プロセスを1つしか止められないため、ここで止め
 * なければ後の段が新しい engine を起こし続ける。殺された子プロセスが残す成果物は前回の実行の
 * ものであり、新しい結果として読むと、取り消したはずの画面が古い指摘で埋まる。
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

/** 解析の結末。取り消しは失敗と区別する(利用者が止めた結果であり、直すところが無い)。 */
export type AnalysisOutcome = "completed" | "failed" | "cancelled";

/** 取り消されたかを問い合わせる関数。段の切れ目と、起動が戻るたびに見る。 */
export type CancelledCheck = () => boolean;

/**
 * この起動が取り消されたか。子プロセスを殺された起動は終了コードが負になる
 * (engine 自身は負を返さない)ため、失敗ではなく取り消しとして扱う。
 */
function stopped(cancelled: CancelledCheck, result?: EngineResult): boolean {
  return cancelled() || (result !== undefined && result.exitCode < 0);
}

/**
 * 解析を1回走らせる。戻り値は結末であり、failed には engine の非ゼロ終了(構文解析に失敗した
 * 資産がある状態)も含む。cancelled を返したときは、済んだ段までの結果が画面に残る。
 */
export async function runAnalysis(
  request: AnalysisRequest,
  handlers: AnalysisHandlers,
  cancelled: CancelledCheck = () => false,
): Promise<AnalysisOutcome> {
  const paths = await window.cobolInsight.getOutputPaths();
  if (cancelled()) {
    return "cancelled";
  }

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
    if (stopped(cancelled, result)) {
      return "cancelled";
    }
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
    if (cancelled()) {
      return "cancelled";
    }
    handlers.onInventory({ status: "error", message: messageOf(error) }, null, null);
    scanFailed = true;
  }
  if (cancelled()) {
    return "cancelled";
  }

  handlers.onStage("lint");
  let lintFailed: boolean;
  try {
    const result = await window.cobolInsight.runLint({
      inputDir: request.inputDir,
      sarifFile: paths.lintSarif,
      copybookPaths: request.copybookPaths,
      ruleConfigFile: paths.ruleConfig,
      // 定義ファイルが未作成でも常に渡す。engine の UserRuleLoader は無いファイルを空として扱う。
      userRulesFile: paths.userRules,
    });
    if (stopped(cancelled, result)) {
      return "cancelled";
    }
    handlers.onFindings({ status: "ready", items: await readFindings(result.outputs.sarif) });
    lintFailed = false;
  } catch (error) {
    if (cancelled()) {
      return "cancelled";
    }
    handlers.onFindings({ status: "error", message: messageOf(error) });
    lintFailed = true;
  }
  if (cancelled()) {
    return "cancelled";
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
    if (stopped(cancelled, result)) {
      return "cancelled";
    }
    handlers.onSqlAdvice({ status: "ready", items: await readFindings(result.outputs.sarif) });
    sqlFailed = false;
  } catch (error) {
    if (cancelled()) {
      return "cancelled";
    }
    handlers.onSqlAdvice({ status: "error", message: messageOf(error) });
    sqlFailed = true;
  }

  return scanFailed || lintFailed || sqlFailed ? "failed" : "completed";
}

/** 書かれた SARIF を読む。出力先が返らなければ検出結果そのものが無い。 */
async function readFindings(sarifPath: string | undefined): Promise<SarifFinding[]> {
  if (sarifPath === undefined) {
    throw new Error("検出結果の出力先を解析エンジンから受け取れませんでした。");
  }
  return window.cobolInsight.readSarif(sarifPath);
}

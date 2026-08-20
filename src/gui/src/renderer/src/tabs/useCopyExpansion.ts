/**
 * COPY 展開の取り回し。scan が書いた対応表と、注記行を補うためのコピー句の原本を読む。
 * 展開そのものは engine の前処理が供給源であり、画面は差し込む場所と見せ方だけを決める。
 */

import { useEffect, useState } from "react";
import type { AssetInventoryItem, CopyExpansion } from "../../../shared/engine-api";
import { messageOf } from "../services/analysis";
import type { CopyStatement } from "../screens/viewer/copybookLookup";
import {
  copyExpansionFile,
  expansionsFor,
  type CopyExpansionState,
} from "../screens/viewer/viewerModel";

/** 読んでいない状態。参照を固定して余分な再描画を起こさない。 */
const IDLE: CopyExpansionState = { status: "idle" };

/**
 * 展開が参照するコピー句の原本を読む。engine が解決した位置(資産フォルダからの相対パス、
 * 資産フォルダの外にあるコピー句は絶対パス)をそのまま当て、境界検査の基準として資産フォルダと
 * コピー句探索パスを順に試す。読めなかったコピー句と復号非対応のコピー句は載せない。
 */
async function loadCopybookLines(
  expansions: readonly CopyExpansion[],
  bases: readonly string[],
  codepageOf: (path: string) => string | null,
): Promise<Map<string, readonly string[]>> {
  const lines = new Map<string, readonly string[]>();
  for (const path of new Set(expansions.map((expansion) => expansion.copybookPath))) {
    for (const base of bases) {
      try {
        const result = await window.cobolInsight.readSourceText({
          inputDir: base,
          path,
          codepage: codepageOf(path),
        });
        if (!result.unsupported) {
          lines.set(path, result.text.split(/\r\n|\n|\r/));
        }
        break;
      } catch {
        // この基準では読めなかった。次の探索先を当てる。
      }
    }
  }
  return lines;
}

/**
 * COPY 展開を読む。閉じているあいだと COPY 文を持たない資産では読まない。
 *
 * @param open 展開を開いているか。
 * @param inputDir 資産フォルダ。null は未確定。
 * @param dbPath プロジェクトファイル。null は未解析。
 * @param path 表示中の資産の相対パス。
 * @param statements 原本から検出した COPY 文。
 * @param inventory 資産一覧。コピー句の文字コードを引くために使う。
 * @param copybookPaths コピー句探索パス。資産フォルダの次に当てる。
 * @param codepage 表示中の資産の文字コード。一覧に無いコピー句へはこれを当てる。
 */
export function useCopyExpansion(
  open: boolean,
  inputDir: string | null,
  dbPath: string | null,
  path: string,
  statements: readonly CopyStatement[],
  inventory: readonly AssetInventoryItem[],
  copybookPaths: readonly string[],
  codepage: string | null,
): CopyExpansionState {
  const [state, setState] = useState<CopyExpansionState>(IDLE);

  useEffect(() => {
    if (!open || statements.length === 0 || inputDir === null || dbPath === null || path === "") {
      setState(IDLE);
      return;
    }
    let current = true;
    setState({ status: "loading" });
    void (async () => {
      try {
        const data = await window.cobolInsight.readCopyExpansion(copyExpansionFile(dbPath));
        const expansions = expansionsFor(data, path);
        const codepageByPath = new Map(inventory.map((item) => [item.path, item.codepage]));
        const copybookLines = await loadCopybookLines(
          expansions,
          [inputDir, ...copybookPaths],
          (target) => codepageByPath.get(target) ?? codepage,
        );
        if (current) setState({ status: "ready", expansions, copybookLines });
      } catch (error) {
        if (current) setState({ status: "error", message: messageOf(error) });
      }
    })();
    return () => {
      current = false;
    };
  }, [open, statements, inputDir, dbPath, path, inventory, copybookPaths, codepage]);

  return state;
}

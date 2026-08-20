/**
 * 逐語対訳の成果物を読む取り回し。行の対応表が空の資産では translate を1度だけ起こして作り、
 * 読み直す。生成そのものと対応表は engine が供給源であり、画面は起動と読取だけを担う。
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { messageOf } from "../services/analysis";
import { transpileOutDir, type TranspileState } from "../screens/viewer/viewerModel";

/** 読んでいない状態。参照を固定して余分な再描画を起こさない。 */
const IDLE: TranspileState = { status: "idle" };

export interface TranspileHandle {
  readonly state: TranspileState;
  /** 対訳を作り直す。生成物だけが失われている場合と、自動生成のあとの再試行で使う。 */
  readonly regenerate: () => Promise<void>;
}

/**
 * 対訳の生成物と行対応表を読む。
 *
 * @param enabled 読んでよいか。COBOL 本体でない資産と、対訳を開いていないあいだは偽にする。
 * @param inputDir 資産フォルダ。null は未確定。
 * @param dbPath プロジェクトファイル。null は未解析。
 * @param path 対訳を読む COBOL 本体の相対パス。
 * @param copybookPaths コピー句探索パス。
 */
export function useTranspile(
  enabled: boolean,
  inputDir: string | null,
  dbPath: string | null,
  path: string,
  copybookPaths: readonly string[],
): TranspileHandle {
  const [state, setState] = useState<TranspileState>(IDLE);
  // 対応表が空の資産へ translate を繰り返し起こさないよう、起動済みの組を覚える。
  const generated = useRef<Set<string>>(new Set());

  const ready = enabled && inputDir !== null && dbPath !== null && path !== "";

  useEffect(() => {
    if (!ready || inputDir === null || dbPath === null) {
      setState(IDLE);
      return;
    }
    let current = true;
    const outDir = transpileOutDir(dbPath);
    const key = `${outDir}|${path}`;
    setState({ status: "loading" });
    void (async () => {
      try {
        let artifacts = await window.cobolInsight.readTranspileArtifacts({
          outDir,
          dbPath,
          cobolRelPath: path,
        });
        if (artifacts.lineMap.length === 0 && !generated.current.has(key)) {
          generated.current.add(key);
          await window.cobolInsight.runTranspile({
            inputDir,
            copybookPaths: [...copybookPaths],
            db: dbPath,
            language: "both",
            outDir,
          });
          artifacts = await window.cobolInsight.readTranspileArtifacts({
            outDir,
            dbPath,
            cobolRelPath: path,
          });
        }
        if (current) {
          setState({ status: "ready", files: artifacts.files, lineMap: artifacts.lineMap });
        }
      } catch (error) {
        if (current) setState({ status: "error", message: messageOf(error) });
      }
    })();
    return () => {
      current = false;
    };
  }, [ready, inputDir, dbPath, path, copybookPaths]);

  const regenerate = useCallback(async (): Promise<void> => {
    if (inputDir === null || dbPath === null || path === "") {
      return;
    }
    const outDir = transpileOutDir(dbPath);
    generated.current.add(`${outDir}|${path}`);
    setState({ status: "loading" });
    try {
      await window.cobolInsight.runTranspile({
        inputDir,
        copybookPaths: [...copybookPaths],
        db: dbPath,
        language: "both",
        outDir,
      });
      const artifacts = await window.cobolInsight.readTranspileArtifacts({
        outDir,
        dbPath,
        cobolRelPath: path,
      });
      setState({ status: "ready", files: artifacts.files, lineMap: artifacts.lineMap });
    } catch (error) {
      setState({ status: "error", message: messageOf(error) });
    }
  }, [inputDir, dbPath, path, copybookPaths]);

  return { state, regenerate };
}

import type { ReactElement, ReactNode } from "react";
import type { ScreenMode } from "../state/appState";
import { RunningIndicator } from "../components/RunningIndicator";

export interface ScreenFrameProps {
  /** 解析ライフサイクル。この値で4状態を描き分ける。 */
  mode: ScreenMode;
  /** empty 状態で示すプレースホルダ(各画面が誘導文を与える)。 */
  empty: ReactNode;
  /** running 状態の見出し(既定「解析を実行しています…」)。 */
  runningTitle?: string;
  /** running 状態で示す段の並び(既定は3段)。 */
  runningStages?: readonly string[];
  /** running 状態で強調する現在の段(1始まり)。 */
  activeStage?: number;
  /** error 状態で本体の上に添える警告バナー(省略時はバナーなし)。 */
  errorBanner?: ReactNode;
  /** results / error 状態で表示する本体。 */
  children: ReactNode;
}

/**
 * 画面の4状態(empty/running/results/error)枠。各画面はこの枠に本体と空状態を差し込むだけで、
 * 実行中インジケータ・エラーバナーの扱いを共通化する。results と error はいずれも本体を表示し、
 * error のときだけ本体の上に警告バナーを添える(design hasResults = results || error)。
 */
export function ScreenFrame({
  mode,
  empty,
  runningTitle,
  runningStages,
  activeStage,
  errorBanner,
  children,
}: ScreenFrameProps): ReactElement {
  if (mode === "empty") {
    return <>{empty}</>;
  }
  if (mode === "running") {
    return <RunningIndicator title={runningTitle} stages={runningStages} activeStage={activeStage} />;
  }
  return (
    <>
      {mode === "error" && errorBanner !== undefined ? errorBanner : null}
      {children}
    </>
  );
}

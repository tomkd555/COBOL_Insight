import { Fragment, type ReactElement } from "react";

/**
 * 実行中に提示する解析段。「▶ 解析実行」が起動するサブコマンドの順序(scan → lint → sql-advise)を
 * 単一の正とし、現在の段を AppState の runStage が指す。lint は構文・制御フロー・データフローの
 * ルール段を 1 プロセスで実行するため、GUI からは内部の段の進行を観測できない。観測できない段を
 * 強調しないよう、GUI が起動を観測できるサブコマンド単位の 3 段だけを提示する。
 */
export const RUN_STAGES: readonly string[] = [
  "第1段 資産の走査と構文解析",
  "第2段 バグ検出",
  "第3段 SQL助言",
];

export interface RunningIndicatorProps {
  /** 見出し。既定「解析を実行しています…」。画面ごとに上書きする(例: バグ検出、呼出関係の構築)。画面の題目は Screen の隠し見出し(h2)が担うため h3 で描く。 */
  title?: string;
  /** 提示する段の並び。既定は RUN_STAGES の3段(scan → lint → sql-advise)。 */
  stages?: readonly string[];
  /** 現在の段(1始まり)。指定時は該当段を強調し aria-current=step にする。 */
  activeStage?: number;
}

/**
 * 実行中インジケータ。スピナーと見出しに続けて、解析段の進行を「→」区切りで提示する。
 * aria-live=polite の status として読み上げ、視覚以外にも状況を伝える。
 */
export function RunningIndicator({
  title = "解析を実行しています…",
  stages = RUN_STAGES,
  activeStage,
}: RunningIndicatorProps): ReactElement {
  return (
    <div className="ci-running" role="status" aria-live="polite">
      <div className="ci-running__box">
        <span className="ci-running__spinner" aria-hidden="true" />
        <div className="ci-running__body">
          <h3 className="ci-running__title">{title}</h3>
          <p className="ci-running__stages">
            {stages.map((label, index) => {
              const active = activeStage === index + 1;
              return (
                <Fragment key={label}>
                  {index > 0 ? (
                    <span className="ci-running__arrow" aria-hidden="true">
                      {" → "}
                    </span>
                  ) : null}
                  <span
                    className={active ? "ci-running__stage ci-running__stage--active" : "ci-running__stage"}
                    aria-current={active ? "step" : undefined}
                  >
                    {label}
                  </span>
                </Fragment>
              );
            })}
          </p>
        </div>
      </div>
    </div>
  );
}

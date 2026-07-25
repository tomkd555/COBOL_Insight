import { Fragment, type ReactElement } from "react";

/**
 * 実行中に提示する解析段。「▶ 解析実行」が起動するサブコマンドの順序(scan → lint → sql-advise)を
 * 単一の正とし、現在の段を AppState の runStage が指す。design:316 は lint 内部のルール段
 * (構文 → 制御フロー → データフロー)を並べるが、lint は 1 プロセスで 3 段を実行するため GUI から
 * 段の進行を観測できない。観測できない段を強調しないよう、GUI が観測できる 3 段を提示する。
 */
export const RUN_STAGES: readonly string[] = [
  "第1段 資産の走査と構文解析(scan)",
  "第2段 バグ検出(lint)",
  "第3段 SQL助言(sql-advise)",
];

export interface RunningIndicatorProps {
  /** 見出し。既定「解析を実行しています…」。画面ごとに上書きする(design はバグ検出/呼出構築など)。 */
  title?: string;
  /** 提示する段の並び。既定は3段(構文→制御フロー→データフロー)。 */
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
          <p className="ci-running__title">{title}</p>
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

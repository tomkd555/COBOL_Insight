import type { ReactElement } from "react";
import { useProject } from "../state/projectStore";
import {
  useWorkbench,
  useWorkbenchDispatch,
  type BottomView,
} from "../state/workbenchStore";
import { FindingsTable } from "./FindingsTable";

/** 下部パネルに並べる面。 */
const VIEWS: readonly { readonly id: BottomView; readonly label: string }[] = [
  { id: "findings", label: "指摘" },
  { id: "log", label: "実行ログ" },
];

export interface BottomPanelProps {
  onOpen: (path: string, line: number) => void;
}

/** 実行ログ。engine の起動と結果を、起きた順に並べる。 */
function RunLog(): ReactElement {
  const project = useProject();
  if (project.runLog.length === 0) {
    return <p className="ci-log__blank">まだ解析していません。</p>;
  }
  return (
    <ol className="ci-log">
      {project.runLog.map((entry) => (
        <li key={entry.id} className={entry.failed ? "ci-log__row ci-log__row--failed" : "ci-log__row"}>
          <span className="ci-log__time">{entry.time}</span>
          <span className="ci-log__text">{entry.text}</span>
        </li>
      ))}
    </ol>
  );
}

/**
 * 下部パネル。指摘の表と実行ログを切り替える。面の切り替えは ARIA のタブ規約に従う。
 */
export function BottomPanel({ onOpen }: BottomPanelProps): ReactElement {
  const workbench = useWorkbench();
  const dispatch = useWorkbenchDispatch();

  return (
    <section className="ci-bottom" aria-label="指摘と実行ログ">
      <div className="ci-bottom__head">
        <div className="ci-bottom__tabs" role="tablist" aria-label="下部パネルの面">
          {VIEWS.map((view) => {
            const selected = workbench.bottomView === view.id;
            return (
              <button
                key={view.id}
                type="button"
                role="tab"
                id={`ci-bottom-tab-${view.id}`}
                aria-selected={selected}
                aria-controls={`ci-bottom-panel-${view.id}`}
                tabIndex={selected ? 0 : -1}
                data-testid={`bottom-${view.id}`}
                className={selected ? "ci-bottom__tab ci-bottom__tab--active" : "ci-bottom__tab"}
                onClick={() => dispatch({ type: "SHOW_BOTTOM", view: view.id })}
              >
                {view.label}
              </button>
            );
          })}
        </div>
        <button
          type="button"
          className="ci-bottom__collapse"
          aria-label="下部パネルを畳む"
          onClick={() => dispatch({ type: "TOGGLE_BOTTOM" })}
        >
          ▾
        </button>
      </div>
      <div
        className="ci-bottom__body"
        role="tabpanel"
        id={`ci-bottom-panel-${workbench.bottomView}`}
        aria-labelledby={`ci-bottom-tab-${workbench.bottomView}`}
      >
        {workbench.bottomView === "findings" ? <FindingsTable onOpen={onOpen} /> : <RunLog />}
      </div>
    </section>
  );
}

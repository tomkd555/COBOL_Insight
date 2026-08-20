import type { ReactElement } from "react";
import {
  singletonTab,
  singletonTabTitle,
  useWorkbench,
  useWorkbenchDispatch,
} from "../state/workbenchStore";

/** アクティビティバーの1項目。資産一覧だけが側パネルを開き、他はタブを開く。 */
interface ActivityItem {
  readonly id: string;
  readonly label: string;
  /** 記号。読み上げからは外し、名前は label が担う。 */
  readonly symbol: string;
}

const ITEMS: readonly ActivityItem[] = [
  { id: "explorer", label: "エクスプローラー", symbol: "▤" },
  { id: "graph", label: singletonTabTitle("graph"), symbol: "⛓" },
  { id: "rules", label: singletonTabTitle("rules"), symbol: "✓" },
  { id: "report", label: singletonTabTitle("report"), symbol: "▦" },
  { id: "settings", label: singletonTabTitle("settings"), symbol: "⚙" },
];

/**
 * 左端のアイコン列。資産一覧は側パネルの開閉、それ以外は対応するタブを開く。
 * 現在の場所は、資産一覧なら側パネルの開閉、他なら選択中のタブと一致するかで示す。
 */
export function ActivityBar(): ReactElement {
  const workbench = useWorkbench();
  const dispatch = useWorkbenchDispatch();

  function select(id: string): void {
    if (id === "explorer") {
      dispatch({ type: "SHOW_SIDE", view: "explorer" });
      return;
    }
    if (id === "graph" || id === "rules" || id === "report" || id === "settings") {
      dispatch({ type: "OPEN_TAB", tab: singletonTab(id) });
    }
  }

  function isCurrent(id: string): boolean {
    return id === "explorer"
      ? workbench.sideVisible && workbench.sideView === "explorer"
      : workbench.activeTabId === id;
  }

  return (
    <nav className="ci-activitybar" aria-label="機能の切り替え">
      <ul className="ci-activitybar__list">
        {ITEMS.map((item) => {
          const current = isCurrent(item.id);
          return (
            <li key={item.id} className="ci-activitybar__item">
              <button
                type="button"
                className={
                  current ? "ci-activitybar__button ci-activitybar__button--current" : "ci-activitybar__button"
                }
                aria-label={item.label}
                aria-current={current ? "true" : undefined}
                data-testid={`activity-${item.id}`}
                onClick={() => select(item.id)}
              >
                <span className="ci-activitybar__symbol" aria-hidden="true">
                  {item.symbol}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

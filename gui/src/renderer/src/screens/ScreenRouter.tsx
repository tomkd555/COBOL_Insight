import type { ReactElement } from "react";
import type { ScreenId } from "../shell/screens";
import { ExplorerScreen } from "./explorer/ExplorerScreen";
import { GraphScreen } from "./graph/GraphScreen";
import { FindingsScreen } from "./findings/FindingsScreen";
import { SqlAdviseScreen } from "./findings/SqlAdviseScreen";
import { ViewerScreen } from "./viewer/ViewerScreen";
import { DiffScreen } from "./diff/DiffScreen";
import { ReportScreen } from "./report/ReportScreen";
import { SettingsScreen } from "./settings/SettingsScreen";

export interface ScreenRouterProps {
  screen: ScreenId;
}

/** 画面ルーティングの分岐点。state.screen に対応する画面本体を返す。 */
export function ScreenRouter({ screen }: ScreenRouterProps): ReactElement {
  if (screen === "explorer") return <ExplorerScreen />;
  if (screen === "graph") return <GraphScreen />;
  if (screen === "findings") return <FindingsScreen />;
  if (screen === "sql") return <SqlAdviseScreen />;
  if (screen === "viewer") return <ViewerScreen />;
  if (screen === "diff") return <DiffScreen />;
  if (screen === "report") return <ReportScreen />;
  return <SettingsScreen />;
}

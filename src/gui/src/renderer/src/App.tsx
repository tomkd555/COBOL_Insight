import type { ReactElement } from "react";
import { Shell } from "./shell/Shell";
import { ProjectProvider } from "./state/projectStore";
import { SettingsProvider } from "./state/settingsStore";
import { WorkbenchProvider } from "./state/workbenchStore";

/**
 * アプリのルート。3つの store でシェルを包む。
 * プロジェクト(engine から得たもの)・作業面(タブとパネル)・設定(保存するもの)を分けて持つ。
 */
export function App(): ReactElement {
  return (
    <SettingsProvider>
      <ProjectProvider>
        <WorkbenchProvider>
          <Shell />
        </WorkbenchProvider>
      </ProjectProvider>
    </SettingsProvider>
  );
}

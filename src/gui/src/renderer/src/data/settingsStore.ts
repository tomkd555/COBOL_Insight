/**
 * 画面の設定の保存と復元。設定画面で変えた値は、次に起動したときも同じでなければならない。
 *
 * 保存する ID の集合は AppState から直に取り出す。設定画面が engine へ渡すために使う
 * disabledRuleIds はカタログの並びで返すため、カタログの取り込みが終わる前に保存すると
 * 空になってしまう。保存はカタログの状態に依存させない。
 */

import type { AppSettings } from "../../../shared/appSettings";
import type { Action } from "../state/appReducer";
import type { AppState } from "../state/appState";

/** 現在の状態から保存する設定を組む。 */
export function toAppSettings(state: AppState): AppSettings {
  return {
    disabledRules: Object.keys(state.rulesDisabled)
      .filter((id) => state.rulesDisabled[id] === true)
      .sort(),
    severityThreshold: state.severityThreshold,
    defaultEncoding: state.defaultEncoding,
    copybookPaths: [...state.project.copybookPaths],
  };
}

/** 保存してある設定を読んで画面へ反映する。起動時に1回だけ呼ぶ。 */
export async function restoreSettings(dispatch: (action: Action) => void): Promise<void> {
  const settings = await window.cobolInsight.readSettings();
  dispatch({ type: "RESTORE_SETTINGS", settings });
}

/** 設定を保存する。設定画面の値が変わるたびに呼ぶ。 */
export async function saveSettings(state: AppState): Promise<void> {
  await window.cobolInsight.writeSettings(toAppSettings(state));
}

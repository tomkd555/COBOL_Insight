/**
 * 起動時の取り込みと、設定の保存。engine のルール一覧と保存済みの設定を読み、画面の store へ配る。
 */

import type { Dispatch } from "react";
import type { AppSettings } from "../../../shared/appSettings";
import type { ProjectAction } from "../state/projectStore";
import { toAppSettings, type SettingsAction, type SettingsState } from "../state/settingsStore";

/**
 * ルール一覧と利用者定義ルールを engine から取り込む。起動時、定義を保存した直後、および
 * 有効・無効を切り替えた直後に呼ぶ。各ルールが検出に効くかどうかは、engine が
 * rules-config.json を読んで返す enabled が唯一の答えである。
 */
export async function loadRuleCatalog(dispatch: Dispatch<ProjectAction>): Promise<void> {
  const paths = await window.cobolInsight.getOutputPaths();
  const [catalog, userRules] = await Promise.all([
    window.cobolInsight.listRules({
      userRulesFile: paths.userRules,
      ruleConfigFile: paths.ruleConfig,
    }),
    window.cobolInsight.readUserRules(paths.userRules),
  ]);
  dispatch({ type: "SET_USER_RULES", rules: userRules.rules });
  dispatch({
    type: "SET_CATALOG",
    entries: catalog.rules,
    userRuleErrors: catalog.userRuleErrors,
    ruleConfigWarnings: catalog.ruleConfigWarnings,
  });
}

/** 保存してある設定を読む。起動時に1回だけ呼ぶ。 */
export async function restoreSettings(dispatch: Dispatch<SettingsAction>): Promise<AppSettings> {
  const settings = await window.cobolInsight.readSettings();
  dispatch({ type: "RESTORE", settings });
  return settings;
}

/** 設定を保存する。設定の値または領域の寸法が変わるたびに呼ぶ。 */
export async function saveSettings(
  state: SettingsState,
  paneSizes: Readonly<Record<string, number>>,
): Promise<void> {
  await window.cobolInsight.writeSettings(toAppSettings(state, paneSizes));
}

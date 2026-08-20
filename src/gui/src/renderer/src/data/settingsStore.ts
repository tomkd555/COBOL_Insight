/**
 * 画面の設定の保存と復元。設定画面で変えた値は、次に起動したときも同じでなければならない。
 *
 * 置き場所は2つある。重大度のしきい値・既定の文字コード・コピー句探索パス・分割ペインの寸法は
 * settings.json、無効にしたルールは engine も読む rules-config.json である。後者を分けるのは、
 * ルールの有効・無効をファイルからも画面からも同じように扱えるようにするためである。
 *
 * 保存する ID の集合は AppState から直に取り出す。設定画面が engine へ渡すために使う
 * disabledRuleIds はカタログの並びで返すため、カタログの取り込みが終わる前に保存すると
 * 空になってしまう。保存はカタログの状態に依存させない。
 */

import { RULE_CONFIG_VERSION, type RuleConfigFile } from "../../../shared/engine-api";
import type { AppSettings } from "../../../shared/appSettings";
import type { Action } from "../state/appReducer";
import type { AppState } from "../state/appState";

/** 現在の状態から保存する設定を組む。 */
export function toAppSettings(state: AppState): AppSettings {
  return {
    severityThreshold: state.severityThreshold,
    defaultEncoding: state.defaultEncoding,
    copybookPaths: [...state.project.copybookPaths],
    paneSizes: { ...state.paneWidths },
  };
}

/** 現在の状態から、engine が読むルールの設定を組む。 */
export function toRuleConfig(state: AppState): RuleConfigFile {
  return {
    version: RULE_CONFIG_VERSION,
    disabledRules: Object.keys(state.rulesDisabled)
      .filter((id) => state.rulesDisabled[id] === true)
      .sort(),
  };
}

/** 保存してある設定を読んで画面へ反映する。起動時に1回だけ呼ぶ。 */
export async function restoreSettings(dispatch: (action: Action) => void): Promise<void> {
  const paths = await window.cobolInsight.getOutputPaths();
  const [settings, ruleConfig] = await Promise.all([
    window.cobolInsight.readSettings(),
    window.cobolInsight.readRuleConfig(paths.ruleConfig),
  ]);
  dispatch({ type: "RESTORE_SETTINGS", settings });
  if (ruleConfig.disabledRules.length > 0) {
    dispatch({ type: "SET_RULES_ENABLED", ids: ruleConfig.disabledRules, enabled: false });
  }
}

/** 設定を保存する。設定画面の値が変わるたびに呼ぶ。 */
export async function saveSettings(state: AppState): Promise<void> {
  const paths = await window.cobolInsight.getOutputPaths();
  await Promise.all([
    window.cobolInsight.writeSettings(toAppSettings(state)),
    window.cobolInsight.writeRuleConfig(paths.ruleConfig, toRuleConfig(state)),
  ]);
}

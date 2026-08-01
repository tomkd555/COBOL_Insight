/**
 * ルールカタログの取り込み。engine の `rules` サブコマンドから一覧を受け、併せて
 * 利用者定義ルールの定義ファイルを読む。起動時に1回と、定義を保存した直後に呼ぶ。
 *
 * カタログ本体は data/ruleCatalog がモジュール共通で保ち、AppState は取り込み世代と
 * 定義の誤りだけを持つ。画面の各所が ruleOf を同期的に引けるようにするためである。
 */

import type { Action } from "../state/appReducer";
import { setRuleCatalog } from "./ruleCatalog";

export async function loadRuleCatalog(dispatch: (action: Action) => void): Promise<void> {
  const paths = await window.cobolInsight.getOutputPaths();
  const [catalog, userRules] = await Promise.all([
    window.cobolInsight.listRules({ userRulesFile: paths.userRules }),
    window.cobolInsight.readUserRules(paths.userRules),
  ]);
  setRuleCatalog(catalog.rules);
  dispatch({ type: "SET_USER_RULES", rules: userRules.rules });
  dispatch({ type: "SET_RULE_CATALOG", userRuleErrors: catalog.userRuleErrors });
}

/**
 * 状態遷移の単一の正。design の DCLogic のメソッド(nav/setMode/startRun/jump/pop)を
 * 純粋な reducer へ移植する。副作用(setTimeout・DOM スクロール)は持たず、トーストの自動消滅は
 * Toast コンポーネント側のタイマーに委ね、実行段の進行は SET_RUN_STAGE の外部駆動に委ねる。
 *
 * 集合の遷移そのものは各画面の純関数(settingsModel の toggleRule・setRulesEnabled、diffModel の
 * setDecision)を再利用し、遷移規則の定義をここへ二重に持たない。
 */

import type {
  AppState,
  ArtifactState,
  AssetTypeFilter,
  DiffMode,
  FindingSort,
  FixDecision,
  GraphArtifactState,
  GraphNodeKind,
  ImportPatch,
  ProjectPatch,
  ReportFormat,
  RunStage,
  ScreenMode,
  SourceLang,
  SplitPaneId,
} from "./appState";
import type { AssetInventoryItem, SarifFinding } from "../../../shared/engine-api";
import type { Severity } from "../components/severity";
import { setDecision } from "../screens/diff/diffModel";
import { setRulesEnabled, toggleRule } from "../screens/settings/settingsModel";
import type { ScreenId } from "../shell/screens";

export type Action =
  | { type: "NAV"; screen: ScreenId }
  | { type: "SET_MODE"; mode: ScreenMode }
  | { type: "START_RUN" }
  | { type: "SET_RUN_STAGE"; stage: RunStage }
  | { type: "FINISH_RUN"; toast?: string; failed?: boolean }
  | { type: "CANCEL_RUN"; toast?: string }
  | { type: "SET_PROJECT"; project: ProjectPatch }
  | { type: "SET_INVENTORY"; result: ArtifactState<AssetInventoryItem>; dbPath?: string }
  | { type: "SET_FINDINGS"; result: ArtifactState<SarifFinding> }
  | { type: "SET_SQL_ADVICE"; result: ArtifactState<SarifFinding> }
  | { type: "SET_GRAPH"; result: GraphArtifactState }
  | { type: "TOGGLE_GRAPH_KIND"; kind: GraphNodeKind }
  | { type: "TOGGLE_GRAPH_EXPANDED"; id: string }
  | { type: "SELECT_GRAPH_NODE"; id: string }
  | { type: "JUMP"; file: string; line: number | null; from: string }
  | { type: "SET_SOURCE_FILE"; file: string }
  | { type: "SET_SOURCE_LANG"; lang: SourceLang }
  | { type: "TOGGLE_COPYBOOK_OPEN" }
  | { type: "SET_LINKED_LINES"; cobolLines: number[]; transpileLines: number[] }
  | { type: "SET_ASSET_SEARCH"; value: string }
  | { type: "SET_ASSET_TYPE"; value: AssetTypeFilter }
  | { type: "SELECT_ASSET"; path: string }
  | { type: "SET_ASSET_ENCODING"; path: string; encoding: string }
  | { type: "TOGGLE_FINDING_SEVERITY"; severity: Severity }
  | { type: "SET_FINDING_RULE"; value: string }
  | { type: "SET_FINDING_FILE"; value: string }
  | { type: "SET_FINDING_TEXT"; value: string }
  | { type: "SET_FINDING_SORT"; sort: FindingSort }
  | { type: "SELECT_FINDING"; finding: SarifFinding }
  | { type: "TOGGLE_SQL_SEVERITY"; severity: Severity }
  | { type: "SET_SQL_RULE"; value: string }
  | { type: "SET_SQL_FILE"; value: string }
  | { type: "SET_SQL_TEXT"; value: string }
  | { type: "SET_SQL_SORT"; sort: FindingSort }
  | { type: "SELECT_SQL_ADVICE"; finding: SarifFinding }
  | { type: "SELECT_FIX"; relPath: string }
  | { type: "SET_DIFF_MODE"; mode: DiffMode }
  | { type: "SET_FIX_DECISION"; relPath: string; decision: FixDecision }
  | { type: "SET_REPORT_FORMAT"; format: ReportFormat }
  | { type: "SET_REPORT_PATH"; value: string }
  | { type: "SET_IMPORT"; patch: ImportPatch }
  | { type: "SET_RULE_SEARCH"; value: string }
  | { type: "TOGGLE_RULE"; id: string }
  | { type: "SET_RULES_ENABLED"; ids: readonly string[]; enabled: boolean }
  | { type: "SET_SEVERITY_THRESHOLD"; severity: Severity }
  | { type: "SET_DEFAULT_ENCODING"; value: string }
  | { type: "SET_NEW_COPYBOOK_PATH"; value: string }
  | { type: "SET_PANE_WIDTH"; pane: SplitPaneId; width: number }
  | { type: "TOGGLE_GRAPH_DETAIL" }
  | { type: "SHOW_TOAST"; message: string }
  | { type: "DISMISS_TOAST" };

export function appReducer(state: AppState, action: Action): AppState {
  switch (action.type) {
    case "NAV":
      return { ...state, screen: action.screen };

    case "SET_MODE":
      // design setMode: モード変更に伴い選択ノードとトーストをクリアする。
      return { ...state, mode: action.mode, selectedNode: null, toastMsg: null };

    case "START_RUN":
      // 再解析の開始時に前回の成果物を破棄する。段ごとの結果が古い実行と混ざらないようにする。
      return {
        ...state,
        mode: "running",
        runStage: 1,
        toastMsg: null,
        inventory: { status: "none" },
        findings: { status: "none" },
        sqlAdvice: { status: "none" },
        // 破棄した成果物の指摘を詳細ペイン・選択が指し続けないよう、両画面の選択を外す。
        sqlSelected: null,
        findingSelected: null,
        // 呼出関係図も古い実行の結果を残さない。次に画面を開いた時点で callgraph を取り直す。
        graph: { status: "none" },
        graphExpanded: {},
        selectedNode: null,
      };

    case "SET_RUN_STAGE":
      return { ...state, runStage: action.stage };

    case "FINISH_RUN":
      return {
        ...state,
        mode: action.failed === true ? "error" : "results",
        toastMsg: action.toast ?? state.toastMsg,
      };

    case "SET_PROJECT":
      // 指定した項目だけを更新する。全画面がこの project を入力フォルダ・DB・コピー句パスの正とする。
      return {
        ...state,
        project: {
          inputDir: action.project.inputDir ?? state.project.inputDir,
          dbPath: action.project.dbPath ?? state.project.dbPath,
          copybookPaths: action.project.copybookPaths ?? state.project.copybookPaths,
        },
      };

    case "SET_INVENTORY":
      return {
        ...state,
        inventory: action.result,
        project: { ...state.project, dbPath: action.dbPath ?? state.project.dbPath },
      };

    case "SET_FINDINGS":
      return { ...state, findings: action.result };

    case "SET_SQL_ADVICE":
      return { ...state, sqlAdvice: action.result };

    case "CANCEL_RUN":
      return {
        ...state,
        mode: "results",
        toastMsg: action.toast ?? "解析をキャンセルしました（完了分の結果を保持）",
      };

    case "SET_GRAPH":
      return { ...state, graph: action.result };

    case "TOGGLE_GRAPH_KIND":
      return {
        ...state,
        graphTypes: { ...state.graphTypes, [action.kind]: !state.graphTypes[action.kind] },
      };

    case "TOGGLE_GRAPH_EXPANDED":
      // 展開済みなら畳む。畳んだノードの隣接は、他の展開経路が無ければ可視集合から外れる。
      return {
        ...state,
        graphExpanded: { ...state.graphExpanded, [action.id]: !state.graphExpanded[action.id] },
      };

    case "SELECT_GRAPH_NODE":
      return { ...state, selectedNode: action.id };

    case "JUMP":
      // design の jump: viewer へ遷移し、ジャンプ元→先の文言を組み、対訳連携をリセットする。
      return {
        ...state,
        screen: "viewer",
        sourceFile: action.file,
        sourceLine: action.line,
        sourceFrom:
          action.from +
          (action.line !== null
            ? ` から ${action.file}:${action.line} へジャンプ`
            : ` から ${action.file} を表示`),
        copybookOpen: false,
        linkedCobolLines: [],
        linkedTranspileLines: [],
      };

    case "SET_SOURCE_FILE":
      // design onVFile: 表示するファイルを変えたら、ジャンプ元・コピー句展開・対訳連携を初期化する。
      return {
        ...state,
        sourceFile: action.file,
        sourceLine: null,
        sourceFrom: null,
        copybookOpen: false,
        linkedCobolLines: [],
        linkedTranspileLines: [],
      };

    case "SET_SOURCE_LANG":
      // design onPy/onJv: 言語を切り替えると生成行の番号が変わるため、対訳連携を捨てる。
      return { ...state, sourceLang: action.lang, linkedCobolLines: [], linkedTranspileLines: [] };

    case "TOGGLE_COPYBOOK_OPEN":
      return { ...state, copybookOpen: !state.copybookOpen };

    case "SET_LINKED_LINES":
      return { ...state, linkedCobolLines: action.cobolLines, linkedTranspileLines: action.transpileLines };

    case "SET_ASSET_SEARCH":
      return { ...state, assetSearch: action.value };

    case "SET_ASSET_TYPE":
      return { ...state, assetType: action.value };

    case "SELECT_ASSET":
      return { ...state, selectedAsset: action.path };

    case "SET_ASSET_ENCODING":
      return { ...state, encodingSel: { ...state.encodingSel, [action.path]: action.encoding } };

    case "TOGGLE_FINDING_SEVERITY":
      return {
        ...state,
        findingSeverity: {
          ...state.findingSeverity,
          [action.severity]: !state.findingSeverity[action.severity],
        },
      };

    case "SET_FINDING_RULE":
      return { ...state, findingRule: action.value };

    case "SET_FINDING_FILE":
      return { ...state, findingFile: action.value };

    case "SET_FINDING_TEXT":
      return { ...state, findingText: action.value };

    case "SET_FINDING_SORT":
      return { ...state, findingSort: action.sort };

    case "SELECT_FINDING":
      return { ...state, findingSelected: action.finding };

    case "TOGGLE_SQL_SEVERITY":
      return {
        ...state,
        sqlSeverity: { ...state.sqlSeverity, [action.severity]: !state.sqlSeverity[action.severity] },
      };

    case "SET_SQL_RULE":
      return { ...state, sqlRule: action.value };

    case "SET_SQL_FILE":
      return { ...state, sqlFile: action.value };

    case "SET_SQL_TEXT":
      return { ...state, sqlText: action.value };

    case "SET_SQL_SORT":
      return { ...state, sqlSort: action.sort };

    case "SELECT_SQL_ADVICE":
      return { ...state, sqlSelected: action.finding };

    case "SELECT_FIX":
      return { ...state, fixSelected: action.relPath };

    case "SET_DIFF_MODE":
      return { ...state, diffMode: action.mode };

    case "SET_FIX_DECISION":
      // 判定の遷移(同じ判定の押し直しで未判定へ戻す)は diff 画面の純関数を正とする。
      return { ...state, decisions: setDecision(state.decisions, action.relPath, action.decision) };

    case "SET_REPORT_FORMAT":
      return { ...state, reportFormat: action.format };

    case "SET_REPORT_PATH":
      return { ...state, reportPath: action.value };

    case "SET_IMPORT":
      return {
        ...state,
        importText: action.patch.importText ?? state.importText,
        importKind: action.patch.importKind ?? state.importKind,
        importFileName: action.patch.importFileName ?? state.importFileName,
        importColumnFrom: action.patch.importColumnFrom ?? state.importColumnFrom,
        importColumnTo: action.patch.importColumnTo ?? state.importColumnTo,
      };

    case "SET_RULE_SEARCH":
      return { ...state, ruleSearch: action.value };

    case "TOGGLE_RULE":
      // ルールの有効・無効の遷移は設定画面の純関数を正とする。
      return { ...state, rulesDisabled: toggleRule(state.rulesDisabled, action.id) };

    case "SET_RULES_ENABLED":
      return {
        ...state,
        rulesDisabled: setRulesEnabled(state.rulesDisabled, action.ids, action.enabled),
      };

    case "SET_SEVERITY_THRESHOLD":
      return { ...state, severityThreshold: action.severity };

    case "SET_DEFAULT_ENCODING":
      return { ...state, defaultEncoding: action.value };

    case "SET_NEW_COPYBOOK_PATH":
      return { ...state, newCopybookPath: action.value };

    case "SET_PANE_WIDTH":
      // 可動範囲の適用は分割ハンドル(SplitHandle)が担い、ここは受け取った幅をそのまま保つ。
      return { ...state, paneWidths: { ...state.paneWidths, [action.pane]: action.width } };

    case "TOGGLE_GRAPH_DETAIL":
      // 畳んでも幅は保つ。戻したときに畳む前の幅で開く。
      return { ...state, graphDetailCollapsed: !state.graphDetailCollapsed };

    case "SHOW_TOAST":
      return { ...state, toastMsg: action.message };

    case "DISMISS_TOAST":
      return { ...state, toastMsg: null };

    default: {
      // 未処理の action 型をコンパイル時に検出させる網羅性チェック。
      const exhaustive: never = action;
      return exhaustive;
    }
  }
}

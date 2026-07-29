/**
 * アプリ全体の状態モデル。design/COBOL Insight.dc.html の DCLogic(class Component extends
 * DCLogic)の state 形を React へ移植した単一の正である。
 * 画面(screen)・解析ライフサイクル(mode)・各画面のフィルタ/選択/decisions を集約する。
 * mode は全画面で共有する1つの解析ライフサイクル状態で、各画面はこの mode に応じて
 * 空/実行中/結果/エラーの4状態を描き分ける。
 *
 * フィールド名は記述的な英語名を用いる。design 側の短いキー名(aSearch・gTypes 等)との対応は、
 * 各フィールドのコメントに添える。
 */

import type { ImportAssetKind } from "../../../shared/assetImport";
import type { AssetInventoryItem, CallGraphData, SarifFinding } from "../../../shared/engine-api";
import type { Severity } from "../components/severity";
import type { ScreenId } from "../shell/screens";

/** 解析ライフサイクル。全画面が共有し、各画面はこの値で4状態を描き分ける(design mode)。 */
export type ScreenMode = "empty" | "running" | "results" | "error";

/** 実行中に提示する解析段(第1段=scan / 第2段=lint / 第3段=sql-advise)。 */
export type RunStage = 1 | 2 | 3;

/** 資産一覧の種別フィルタ(design aType)。 */
export type AssetTypeFilter = "すべて" | "JCL" | "COBOL" | "コピー句" | "BMS" | "その他";

/**
 * 呼出関係図のノード種別(design gTypes のキー)。値は engine の NodeKind(10種)に、構文解析に
 * 失敗した資産を表す UNANALYZABLE(engine の列挙には無い GUI 限定の種別)を加えた11種。
 * callgraph JSON のノードの kind をそのままフィルタのキーとして扱う。
 */
export type GraphNodeKind =
  | "JOB"
  | "STEP"
  | "PROGRAM"
  | "PARAGRAPH"
  | "DATASET"
  | "DB2_TABLE"
  | "UNRESOLVED"
  | "EXTERNAL_UTILITY"
  | "TRANSACTION"
  | "BMS_MAP"
  | "UNANALYZABLE";

/** 指摘一覧・SQL助言の表でソート可能な列(design fSort に、ルール列を加える)。 */
export type FindingSortColumn = "sev" | "rule" | "file" | "line";

/** ソートの向き。 */
export type SortDirection = "asc" | "desc";

/** ソート状態(列と向きの組)。指摘一覧と SQL助言はそれぞれ独立に持つ。 */
export interface FindingSort {
  readonly column: FindingSortColumn;
  readonly direction: SortDirection;
}

/** ソースビューアの逐語対訳の言語(design lang)。 */
export type SourceLang = "py" | "java";

/** diff のモード(design diffMode)。preview=確認 / apply=書き出し。 */
export type DiffMode = "preview" | "apply";

/** レポート出力形式(design repFmt)。 */
export type ReportFormat = "HTML" | "テキスト";

/** 修正案の判定(design decisions の値)。未判定はキー不在で表す。 */
export type FixDecision = "adopted" | "rejected";

/**
 * CLI サブコマンドの成果物の取得状態。none=未取得、ready=取得済み、error=起動または読取の失敗。
 * 失敗を空配列(0 件)と区別することで、解析失敗を「指摘 0 件」として提示しない。
 */
export type ArtifactState<T> =
  | { readonly status: "none" }
  | { readonly status: "ready"; readonly items: readonly T[] }
  | { readonly status: "error"; readonly message: string };

/**
 * 呼出関係図の取得状態。callgraph は資産エクスプローラーの「▶ 解析実行」では起動せず、
 * 呼出関係図の画面が必要になった時点で起動するため、起動中(loading)を状態として持つ。
 * exitCode は callgraph の終了コード(0=成功 / 1=警告あり / 2=エラーあり)で、非ゼロでも
 * グラフ本体は書かれるため、図を隠さず警告として示すために保持する。
 */
export type GraphArtifactState =
  | { readonly status: "none" }
  | { readonly status: "loading" }
  | { readonly status: "ready"; readonly data: CallGraphData; readonly exitCode: number }
  | { readonly status: "error"; readonly message: string };

/**
 * 解析対象プロジェクト。全画面がここから入力フォルダ・SQLite・コピー句検索パスを参照する。
 * 画面ごとの入力フォルダ定数は持たない。
 */
export interface ProjectState {
  /** 資産フォルダ(scan/lint/sql-advise の位置引数)。未インポートは null。 */
  readonly inputDir: string | null;
  /** scan が書いた SQLite プロジェクトファイル。未解析は null。 */
  readonly dbPath: string | null;
  /** コピー句検索パスの順序付き一覧(--copybook-path、design cpyPaths)。設定画面が編集する。 */
  readonly copybookPaths: string[];
}

/**
 * 幅を保持する分割ペイン。いずれも分割ハンドルの右側のペインで、画面ごとに独立した値を持つ。
 * viewerTranslation はソースビューアの逐語対訳ペイン(左は COBOL 原本)である。
 */
export type SplitPaneId = "explorerDetail" | "graphDetail" | "sqlDetail" | "viewerTranslation";

/** 分割ペインの初期幅と可動範囲(画素)。 */
export interface SplitPaneLimits {
  readonly initial: number;
  readonly min: number;
  readonly max: number;
}

/**
 * 分割ペインの寸法。下限はペインが役目を果たす最小の幅、上限は隣のペインを潰さない幅である。
 * viewerTranslation の下限 240px は、最小ウィンドウ幅 1280px でも COBOL 原本ペインへ
 * 固定形式 80 桁と行番号 5 桁を横スクロールなしで描ける幅を残す。初期幅も同じ条件を満たす。
 */
export const SPLIT_PANES: Record<SplitPaneId, SplitPaneLimits> = {
  explorerDetail: { initial: 330, min: 240, max: 560 },
  graphDetail: { initial: 252, min: 200, max: 480 },
  sqlDetail: { initial: 380, min: 280, max: 640 },
  viewerTranslation: { initial: 520, min: 240, max: 900 },
};

/** SET_IMPORT で更新する項目。省略した項目は現在値を保つ。 */
export interface ImportPatch {
  readonly importText?: string;
  readonly importKind?: ImportAssetKind;
  readonly importFileName?: string;
  readonly importColumnFrom?: number;
  readonly importColumnTo?: number;
}

/** SET_PROJECT で更新する項目。省略した項目は現在値を保つ。 */
export interface ProjectPatch {
  readonly inputDir?: string;
  readonly dbPath?: string;
  readonly copybookPaths?: string[];
}

export interface AppState {
  /** 解析ライフサイクル(全画面共有)。 */
  readonly mode: ScreenMode;
  /** アクティブな画面(タブ)。 */
  readonly screen: ScreenId;
  /** 実行中に強調する解析段。 */
  readonly runStage: RunStage;
  /** 製品バージョン(ステータスバー表示)。 */
  readonly version: string;

  /* 解析実行の結果(全画面共有。画面のアンマウントで失わない) */
  /** 解析対象プロジェクト。 */
  readonly project: ProjectState;
  /** scan → readAssetInventory で得た資産一覧。 */
  readonly inventory: ArtifactState<AssetInventoryItem>;
  /** lint → readSarif で得た指摘(R001〜R031)。 */
  readonly findings: ArtifactState<SarifFinding>;
  /** sql-advise → readSarif で得た SQL 最適化助言(S001〜S006)。 */
  readonly sqlAdvice: ArtifactState<SarifFinding>;

  /* 資産エクスプローラー(scan) */
  /** 名前フィルタ(design aSearch)。 */
  readonly assetSearch: string;
  /** 種別フィルタ(design aType)。 */
  readonly assetType: AssetTypeFilter;
  /** 選択中の資産の相対パス(design selAsset。gui では一意な相対パスで保持)。未選択は空文字。 */
  readonly selectedAsset: string;
  /** 資産(相対パス)ごとの文字コード手動指定(design encSel)。次回の scan で codepageOverrides に反映する。 */
  readonly encodingSel: Record<string, string>;

  /* 呼出関係図(callgraph) */
  /** callgraph → readCallgraphJson で得た呼出関係グラフ。 */
  readonly graph: GraphArtifactState;
  /** ノード種別フィルタの ON/OFF(design gTypes)。 */
  readonly graphTypes: Record<GraphNodeKind, boolean>;
  /** 部分展開の状態。キーは展開したノード ID で、そのノードの隣接を表示に加える(design gColl)。 */
  readonly graphExpanded: Record<string, boolean>;
  /** 選択中ノード ID(design selNode)。 */
  readonly selectedNode: string | null;

  /* 指摘一覧(lint) */
  /** 重大度フィルタの ON/OFF(design fSev)。 */
  readonly findingSeverity: Record<Severity, boolean>;
  /** ルールフィルタ(design fRule)。"all" は全ルール。 */
  readonly findingRule: string;
  /** ファイルフィルタ(design fFile)。"all" は全ファイル。 */
  readonly findingFile: string;
  /** 内容テキスト検索(design fText)。 */
  readonly findingText: string;
  /** ソート状態(列・向きの組、design fSort に向きを加える)。 */
  readonly findingSort: FindingSort;
  /**
   * 選択中の指摘(design には無い)。行のクリック・Enter/Space は選択だけを行い、ソースへの
   * ジャンプは行内の明示的なボタンで行う。選択は表の強調表示にだけ使う。
   */
  readonly findingSelected: SarifFinding | null;
  /** 空状態のバリアント(design emptyVariant)。0=未解析 / 1=指摘0件。 */
  readonly findingsEmptyVariant: number;

  /* ソースビューア(transpile 統合) */
  /** 表示中のソースファイル(design srcFile)。 */
  readonly sourceFile: string;
  /** ジャンプ先の行(design srcLine)。null は行指定なし。 */
  readonly sourceLine: number | null;
  /** ジャンプ元の説明バナー文(design srcFrom)。 */
  readonly sourceFrom: string | null;
  /** コピー句のインライン展開の開閉(design copyOpen)。 */
  readonly copybookOpen: boolean;
  /** 逐語対訳の言語(design lang)。 */
  readonly sourceLang: SourceLang;
  /** hover 相互ハイライトの COBOL 側行(design linkC)。 */
  readonly linkedCobolLines: number[];
  /** hover 相互ハイライトの対訳側行(design linkP)。 */
  readonly linkedTranspileLines: number[];

  /* SQL助言(sql-advise) */
  /**
   * 選択中の SQL 助言(design sqlSel)。詳細ペインが本文と助言を出す対象で、未選択は null。
   * sqlAdvice.items の要素をそのまま持ち、同一位置・同一ルールの重複があっても取り違えない。
   */
  readonly sqlSelected: SarifFinding | null;
  /** 重大度フィルタの ON/OFF。指摘一覧と同じくタブ移動で失わないよう AppState に持つ。 */
  readonly sqlSeverity: Record<Severity, boolean>;
  /** ルールフィルタ。"all" は全ルール。 */
  readonly sqlRule: string;
  /** ファイルフィルタ。"all" は全ファイル。 */
  readonly sqlFile: string;
  /** 内容テキスト検索。 */
  readonly sqlText: string;
  /** ソート状態(列・向きの組)。指摘一覧とは独立に持つ。 */
  readonly sqlSort: FindingSort;

  /* diff(fix) */
  /**
   * 選択中の修正案の相対パス(design fixSel)。未選択は空文字。修正案の並びは engine の出力に
   * 依存し、再解析で位置が変わるため、位置ではなく相対パスで指す。
   */
  readonly fixSelected: string;
  /** diff モード(design diffMode)。 */
  readonly diffMode: DiffMode;
  /** 修正案の相対パス → 判定(design decisions)。未判定はキー不在。 */
  readonly decisions: Record<string, FixDecision>;

  /* レポート出力(report) */
  /** 出力形式(design repFmt)。 */
  readonly reportFormat: ReportFormat;
  /** 出力先フォルダ(design repPath)。空文字はプロジェクトファイルの置き場所を用いることを表す。 */
  readonly reportPath: string;

  /* 端末取込(GUI 専用) */
  /** 貼り付けた本文。桁を切り出す前の原文である。 */
  readonly importText: string;
  /** 取り込む資産の種別。保存先のフォルダと拡張子を決める。 */
  readonly importKind: ImportAssetKind;
  /** 保存するファイル名(拡張子は種別から補う)。 */
  readonly importFileName: string;
  /** 取り込む開始桁(1起点・両端を含む)。 */
  readonly importColumnFrom: number;
  /** 取り込む終了桁(1起点・両端を含む)。 */
  readonly importColumnTo: number;

  /* 設定(GUI 専用) */
  /** ルール検索(design ruleSearch)。 */
  readonly ruleSearch: string;
  /** 無効化したルール ID の集合(design rulesOff)。engine の --disable-rule へ渡す。 */
  readonly rulesDisabled: Record<string, boolean>;
  /** 表示する重大度しきい値(design sevTh)。これより低い重大度は指摘・SQL助言の一覧に出さない。 */
  readonly severityThreshold: Severity;
  /**
   * 既定文字コード(design encDef)。文字コードの検出に失敗した資産で手動指定が無い場合に、
   * 文字コード選択欄とデコードプレビューの初期値として用いる。語彙は資産エクスプローラーの
   * 手動指定(explorer/assetView の MANUAL_ENCODING_OPTIONS)と同じである。
   */
  readonly defaultEncoding: string;
  /** 追加中のコピー句検索パス入力(design newPath)。 */
  readonly newCopybookPath: string;

  /* 分割ペインの幅(GUI 専用) */
  /** 画面ごとの分割ペインの幅(画素)。タブを移動しても保つ。 */
  readonly paneWidths: Record<SplitPaneId, number>;
  /** 呼出関係図の右ペインを畳んでいるか。畳むとペインとハンドルを出さず、図が全幅を使う。 */
  readonly graphDetailCollapsed: boolean;

  /* 横断 */
  /** トースト通知の文言(design toastMsg)。null は非表示。 */
  readonly toastMsg: string | null;
}

/**
 * 製品の初期状態。design のデモ既定(mode:'results')ではなく、解析未実行の空状態から始める
 * (design が持つ状態切替の操作は製品では実装しないため)。フィルタ既定値は design の state 初期値を踏襲する。
 */
export const initialState: AppState = {
  mode: "empty",
  screen: "explorer",
  runStage: 1,
  version: "1.0.0",

  project: { inputDir: null, dbPath: null, copybookPaths: [] },
  inventory: { status: "none" },
  findings: { status: "none" },
  sqlAdvice: { status: "none" },

  assetSearch: "",
  assetType: "すべて",
  selectedAsset: "",
  encodingSel: {},

  graph: { status: "none" },
  graphTypes: {
    JOB: true,
    STEP: true,
    PROGRAM: true,
    PARAGRAPH: true,
    DATASET: true,
    DB2_TABLE: true,
    UNRESOLVED: true,
    EXTERNAL_UTILITY: true,
    TRANSACTION: true,
    BMS_MAP: true,
    UNANALYZABLE: true,
  },
  graphExpanded: {},
  selectedNode: null,

  findingSeverity: { high: true, medium: true, low: true, warning: true },
  findingRule: "all",
  findingFile: "all",
  findingText: "",
  findingSort: { column: "sev", direction: "asc" },
  findingSelected: null,
  findingsEmptyVariant: 0,

  sourceFile: "",
  sourceLine: null,
  sourceFrom: null,
  copybookOpen: false,
  sourceLang: "py",
  linkedCobolLines: [],
  linkedTranspileLines: [],

  sqlSelected: null,
  sqlSeverity: { high: true, medium: true, low: true, warning: true },
  sqlRule: "all",
  sqlFile: "all",
  sqlText: "",
  sqlSort: { column: "sev", direction: "asc" },

  fixSelected: "",
  diffMode: "preview",
  decisions: {},

  reportFormat: "HTML",
  reportPath: "",

  importText: "",
  importKind: "cobol",
  importFileName: "",
  importColumnFrom: 1,
  importColumnTo: 80,

  ruleSearch: "",
  rulesDisabled: {},
  severityThreshold: "warning",
  defaultEncoding: "手動: Shift_JIS",
  newCopybookPath: "",

  paneWidths: {
    explorerDetail: SPLIT_PANES.explorerDetail.initial,
    graphDetail: SPLIT_PANES.graphDetail.initial,
    sqlDetail: SPLIT_PANES.sqlDetail.initial,
    viewerTranslation: SPLIT_PANES.viewerTranslation.initial,
  },
  graphDetailCollapsed: false,

  toastMsg: null,
};

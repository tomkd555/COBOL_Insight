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
import type {
  AssetInventoryItem,
  CallGraphData,
  SarifFinding,
  UserRuleDefinition,
} from "../../../shared/engine-api";
import type { Severity } from "../components/severity";
import type { ScreenId } from "../shell/screens";

/** 解析ライフサイクル。全画面が共有し、各画面はこの値で4状態を描き分ける(design mode)。 */
export type ScreenMode = "empty" | "running" | "results" | "error";

/** 実行中に提示する解析段(第1段=scan / 第2段=lint / 第3段=sql-lint)。 */
export type RunStage = 1 | 2 | 3;

/** 資産一覧の種別フィルタ(design aType)。 */
export type AssetTypeFilter = "すべて" | "JCL" | "COBOL" | "コピー句" | "BMS" | "その他";

/**
 * 呼出関係図のノード種別(design gTypes のキー)。値は engine の NodeKind(10種)に、構文解析に
 * 失敗した資産を表す UNANALYZABLE(engine の列挙には無い GUI 限定の種別)を加えた11種。
 * call-graph JSON のノードの kind をそのままフィルタのキーとして扱う。
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

/** 指摘一覧・SQL指摘の表でソート可能な列(design fSort に、ルール列を加える)。 */
export type FindingSortColumn = "sev" | "rule" | "file" | "line";

/** ソートの向き。 */
export type SortDirection = "asc" | "desc";

/** ソート状態(列と向きの組)。指摘一覧と SQL指摘はそれぞれ独立に持つ。 */
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

/** engine が資産の種別判定に用いる種別名(拡張子ではなく判定結果)。 */
export type DiscoveredAssetKind = "COBOL" | "COPYBOOK" | "JCL" | "BMS";

/** 拡張子と内容の判定が食い違い、内容を優先して取り込んだ1件。 */
export interface KindMismatch {
  readonly path: string;
  readonly byExtension: DiscoveredAssetKind;
  readonly byContent: DiscoveredAssetKind;
}

/**
 * scan がどう資産を走査したか。engine はソースの内容から種別を逆算する単一の走査を行い、
 * 種別を判定できなかったもの(undecided)・読み取れなかったもの(unreadable)・拡張子と内容が
 * 食い違い内容を優先して取り込んだもの(mismatches)を全件、上限による打ち切り(truncated)を
 * 併せて受け取り、画面へ出す。
 */
export interface ScanDiscovery {
  /** 種別を判定できず対象外とした相対パス(全件)。 */
  readonly undecided: readonly string[];
  /** 拡張子と内容が食い違い、内容を優先して取り込んだもの(全件)。 */
  readonly mismatches: readonly KindMismatch[];
  /** 走査の上限に達して打ち切ったか。 */
  readonly truncated: boolean;
  /** 読み取れず対象外とした相対パス(全件)。 */
  readonly unreadable: readonly string[];
}

/**
 * 呼出関係図の取得状態。call-graph は資産エクスプローラーの「▶ 解析実行」では起動せず、
 * 呼出関係図の画面が必要になった時点で起動するため、起動中(loading)を状態として持つ。
 * exitCode は call-graph の終了コード(0=成功 / 1=警告あり / 2=エラーあり)で、非ゼロでも
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
  /** 資産フォルダ(scan/lint/sql-lint の位置引数)。未インポートは null。 */
  readonly inputDir: string | null;
  /** scan が書いた SQLite プロジェクトファイル。未解析は null。 */
  readonly dbPath: string | null;
  /** コピー句検索パスの順序付き一覧(--copybook-path、design cpyPaths)。設定画面が編集する。 */
  readonly copybookPaths: string[];
}

/**
 * 寸法を保持する分割ペイン。補助側(一覧・詳細)が寸法を持ち、コード面は残りを取る。
 * 括弧内はハンドルの向きと、ハンドルのどちら側のペインを操作するかである。
 */
export type SplitPaneId =
  | "explorerDetail" // 資産一覧 右の詳細(幅・after)
  | "graphNodes" // 呼出関係図 左のノード一覧(幅・before)
  | "graphDetail" // 呼出関係図 右の詳細(幅・after)
  | "findingsList" // 指摘一覧 上の一覧(高さ・before)
  | "sqlList" // SQL指摘 上の一覧(高さ・before)
  | "sqlDetail" // SQL指摘 下段の右の詳細(幅・after)
  | "viewerTranslation" // ソースビューア 右の逐語対訳(幅・after)
  | "diffList"; // 修正案の差分 左の一覧(幅・before)

/** 分割ペインの初期の寸法と、可動範囲を導くための2つの下限(画素)。 */
export interface SplitPaneLimits {
  /** 初期の寸法。 */
  readonly initial: number;
  /** このペインが役目を果たす最小。 */
  readonly min: number;
  /** 相手側(コード面)へ必ず残す最小。 */
  readonly oppositeMin: number;
}

/**
 * 分割ペインの寸法。可動上限は画素の定数で持たず、描画のたびに
 * 「コンテナの寸法 − oppositeMin − ハンドルの寸法」で導く(components/SplitHandle の maxSplitSize)。
 * 上限を定数で持つと、ウィンドウが小さいときは上限まで広げると相手が潰れ、大きいときは
 * これ以上広げられない理由が利用者に分からない、の両方が起きるためである。
 *
 * min と oppositeMin の根拠は各行の注記のとおりで、いずれも「そのペインが役目を果たす最小」である。
 */
export const SPLIT_PANES: Record<SplitPaneId, SplitPaneLimits> = {
  // min=固定形式 80 桁 + 余白 / oppositeMin=資産一覧の最小内容幅
  explorerDetail: { initial: 520, min: 400, oppositeMin: 657 },
  graphNodes: { initial: 200, min: 140, oppositeMin: 400 },
  graphDetail: { initial: 260, min: 200, oppositeMin: 400 },
  // min=表の見出し + 2 行 / oppositeMin=コード面に 10 行
  findingsList: { initial: 260, min: 120, oppositeMin: 200 },
  sqlList: { initial: 240, min: 120, oppositeMin: 200 },
  // oppositeMin=SQL 本文に 80 桁
  sqlDetail: { initial: 360, min: 260, oppositeMin: 600 },
  // oppositeMin=原本に固定形式 80 桁 + 行番号
  viewerTranslation: { initial: 480, min: 300, oppositeMin: 600 },
  // oppositeMin=差分 2 面へ各 300
  diffList: { initial: 300, min: 200, oppositeMin: 600 },
};

/**
 * 各ペインの初期の寸法。SPLIT_PANES のキーから組み、ペインを増減しても直す箇所を1つに保つ。
 */
function initialPaneSizes(): Record<SplitPaneId, number> {
  const sizes: Record<string, number> = {};
  for (const [id, limits] of Object.entries(SPLIT_PANES)) {
    sizes[id] = limits.initial;
  }
  return sizes;
}

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
  /** scan の走査の付帯情報。取りこぼしを利用者へ伝えるために持つ。未実行なら null。 */
  readonly scanDiscovery: ScanDiscovery | null;
  /** lint → readSarif で得た指摘(R001〜R031)。 */
  readonly findings: ArtifactState<SarifFinding>;
  /** sql-lint → readSarif で得た SQL 最適化の指摘(S001〜S006)。 */
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

  /* 呼出関係図(call-graph) */
  /** call-graph → readCallgraphJson で得た呼出関係グラフ。 */
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

  /* ソースビューア(translate 統合) */
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

  /* SQL指摘(sql-lint) */
  /**
   * 選択中の SQL 指摘(design sqlSel)。詳細ペインが本文と指摘を出す対象で、未選択は null。
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
  /** 表示する重大度しきい値(design sevTh)。これより低い重大度は指摘・SQL指摘の一覧に出さない。 */
  readonly severityThreshold: Severity;
  /**
   * 既定文字コード(design encDef)。文字コードの検出に失敗した資産で手動指定が無い場合に、
   * 文字コード選択欄とデコードプレビューの初期値として用いる。語彙は資産エクスプローラーの
   * 手動指定(explorer/assetView の MANUAL_ENCODING_OPTIONS)と同じである。
   */
  readonly defaultEncoding: string;
  /** 追加中のコピー句検索パス入力(design newPath)。 */
  readonly newCopybookPath: string;
  /**
   * 保存してある設定を読み終えたか。読み終える前に保存すると、起動時の初期値で保存済みの
   * 設定を上書きしてしまうため、保存はこれが true になってから行う。
   */
  readonly settingsLoaded: boolean;

  /* ルールカタログと利用者定義ルール(GUI 専用) */
  /**
   * カタログの取り込み世代。engine の rules から一覧を受けるたびに増やす。カタログ本体は
   * data/ruleCatalog がモジュール共通で保つため、この値の変化を再描画のきっかけにする。
   * 0 は未取得であり、設定画面は一覧の代わりに読み込み中を示す。
   */
  readonly ruleCatalogGeneration: number;
  /** engine が返した利用者定義ルールの定義の誤り。設定画面へそのまま示す。 */
  readonly userRuleErrors: readonly string[];
  /** 利用者定義ルールの定義。定義ファイル(user-rules.json)の内容と一致させる。 */
  readonly userRules: readonly UserRuleDefinition[];
  /** 説明を開いているルール ID。null はいずれも開いていない。 */
  readonly ruleDetailId: string | null;
  /** 編集中の利用者定義ルール。null は編集していない。 */
  readonly userRuleDraft: UserRuleDefinition | null;
  /** 編集中の定義が既存の何番目か。null は新規追加である。 */
  readonly userRuleDraftIndex: number | null;

  /* 分割ペインの寸法(GUI 専用) */
  /** 画面ごとの分割ペインの寸法(画素)。タブを移動しても保つ。 */
  readonly paneWidths: Record<SplitPaneId, number>;
  /**
   * 分割ハンドルの操作が終わった回数。ドラッグは1画素ごとに寸法を変えるため、その全部を
   * 保存すると1回のドラッグで数十回の書き込みが走る。保存はこの回数の変化だけを合図に行う
   * (時間のしきい値を持たないので、何ミリ秒が適切かという説明のつかない選択が要らない)。
   */
  readonly paneCommitCount: number;
  /** 呼出関係図の右ペインを畳んでいるか。畳むとペインとハンドルを出さず、図が全幅を使う。 */
  readonly graphDetailCollapsed: boolean;
  /**
   * コードを最大化しているか。真のとき各画面は補助領域をハンドルごと出さず、コード面が全体を取る。
   * 1回の作業の間だけ意味を持つ値であり、保存はしない。
   */
  readonly codeFocus: boolean;

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
  scanDiscovery: null,
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
  settingsLoaded: false,

  ruleCatalogGeneration: 0,
  userRuleErrors: [],
  userRules: [],
  ruleDetailId: null,
  userRuleDraft: null,
  userRuleDraftIndex: null,

  paneWidths: initialPaneSizes(),
  paneCommitCount: 0,
  graphDetailCollapsed: false,
  codeFocus: false,

  toastMsg: null,
};

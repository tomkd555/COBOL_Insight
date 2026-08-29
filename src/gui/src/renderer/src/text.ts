/**
 * Every user-visible string in the renderer. The product is Japanese, so the values are Japanese and
 * the keys are English; keeping them all here lets a later wording pass edit one file.
 *
 * Rule names, categories and prose are deliberately absent: the engine is their only source and the
 * screens render what `rules --json` returns.
 */
export const text = {
  app: {
    name: "COBOL Insight",
    run: "解析を実行",
    cancel: "中止",
    running: "解析中",
  },

  activity: {
    explorer: "資産",
    search: "検索",
    rules: "ルール",
    problems: "指摘",
    label: "画面の切り替え",
  },

  sideBar: {
    explorerTitle: "資産エクスプローラー",
    searchTitle: "検索",
    rulesTitle: "ルール",
    problemsTitle: "指摘",
    label: "サイドバー",
    resize: "サイドバーの幅",
  },

  editor: {
    tabs: "開いているファイル",
    close: "閉じる",
    unsaved: "未保存の変更",
    empty: "ファイルを開いていません",
  },

  panel: {
    label: "パネル",
    problems: "指摘",
    output: "出力",
    resize: "パネルの高さ",
    close: "パネルを閉じる",
  },

  status: {
    label: "ステータス",
    noFolder: "資産フォルダ未選択",
    assets: "資産",
    findings: "指摘",
    sqlFindings: "SQL指摘",
    stage: (stage: number, total: number): string => `第 ${stage} / ${total} 段`,
    unit: "件",
  },

  welcome: {
    title: "COBOL Insight",
    lead: "メインフレーム資産のフォルダを選ぶと、走査・検出・SQL検出を順に実行します。",
    selectFolder: "資産フォルダを選ぶ",
    recent: "前回のフォルダ",
    shortcutHint: "コマンドパレットは Ctrl+Shift+P で開きます。",
  },

  explorer: {
    selectFolder: "資産フォルダを選ぶ",
    changeFolder: "フォルダを変更",
    search: "名前で絞り込む",
    searchLabel: "資産の名前で絞り込む",
    typeLabel: "種別で絞り込む",
    tree: "資産ツリー",
    empty: "資産フォルダを選ぶと、ここに資産が並びます。",
    loading: "走査しています…",
    noMatch: "絞り込みに一致する資産がありません。",
    error: "資産一覧を読めませんでした。",
    codepageUnknown: "文字コード不明",
    findingCount: (count: number): string => `指摘 ${count} 件`,
  },

  assetType: {
    all: "すべて",
    cobol: "COBOL",
    copybook: "コピー句",
    jcl: "JCL",
    bms: "BMS",
    other: "その他",
  },

  severity: {
    high: "高",
    medium: "中",
    low: "低",
    warning: "注意",
  },

  problems: {
    title: "指摘",
    empty: "解析を実行すると、ここに指摘が並びます。",
    loading: "検出しています…",
    noMatch: "絞り込みに一致する指摘がありません。",
    clean: "指摘はありません。",
    error: "指摘を読めませんでした。",
    search: "内容で絞り込む",
    searchLabel: "指摘の内容で絞り込む",
    allRules: "ルール: すべて",
    allFiles: "資産: すべて",
    allSources: "出所: すべて",
    thresholdNote: "重大度しきい値より低い指摘は表に出していません。",
    columnSeverity: "重大度",
    columnRule: "ルール",
    columnMessage: "内容",
    columnFile: "資産",
    columnLine: "行",
    sortLabel: "並び順",
    sortSeverity: "重大度順",
    sortFile: "資産順",
    sortRule: "ルール順",
  },

  source: {
    lint: "コード",
    sql: "SQL",
    save: "保存時の検証",
  },

  output: {
    title: "出力",
    empty: "解析を実行すると、ここに実行ログが出ます。",
    clear: "ログを消す",
  },

  sourceView: {
    loading: "本文を読み込んでいます…",
    error: "本文を表示できませんでした。",
    codepage: "文字コード",
    detected: "自動判別",
    lines: (count: number): string => `${count} 行`,
  },

  run: {
    scan: "走査",
    lint: "指摘の検出",
    sqlLint: "SQL指摘の検出",
    started: "解析を開始しました。",
    cancelled: "解析を中止しました（完了した分の結果は残ります）。",
    stageStarted: (stage: string): string => `${stage}を開始しました。`,
    stageDone: (stage: string, count: number): string => `${stage}を終えました（${count} 件）。`,
    stageFailed: (stage: string, reason: string): string => `${stage}に失敗しました。${reason}`,
    scanDone: (count: number): string => `走査を終えました（資産 ${count} 件）。`,
    noFolder: "先に資産フォルダを選んでください。",
  },

  palette: {
    label: "コマンドパレット",
    placeholder: "コマンドを入力",
    noMatch: "一致するコマンドがありません。",
  },

  command: {
    categoryFile: "ファイル",
    categoryView: "表示",
    categoryRun: "実行",
    selectFolder: "資産フォルダを選ぶ",
    run: "解析を実行する",
    cancel: "解析を中止する",
    toggleSideBar: "サイドバーの表示を切り替える",
    togglePanel: "パネルの表示を切り替える",
    showExplorer: "資産エクスプローラーを開く",
    showSearch: "検索を開く",
    showRules: "ルールを開く",
    showProblems: "指摘を開く",
    showOutput: "出力を開く",
    closeTab: "タブを閉じる",
    nextTab: "次のタブへ",
    previousTab: "前のタブへ",
  },

  modal: {
    confirmDiscardTitle: "保存していない変更があります",
    confirmDiscardBody: "閉じると、この資産への編集は失われます。",
    discard: "破棄して閉じる",
    keep: "編集を続ける",
    close: "閉じる",
  },

  toast: {
    close: "通知を閉じる",
  },

  placeholder: {
    notImplemented: "この画面はまだありません。",
  },
} as const;

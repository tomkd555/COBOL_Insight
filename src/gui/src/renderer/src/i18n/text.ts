/**
 * Every user-visible string in the renderer. The product is Japanese, so the values are Japanese and
 * the keys are English; keeping them all here lets a later wording pass edit one file.
 *
 * Rule names, categories and prose are deliberately absent: the engine is their only source and the
 * screens render what `rules --json` returns.
 */

/** The note under an empty required field. The field's label sits right above it, so it is not repeated. */
const REQUIRED = "必須です。";

export const text = {
  app: {
    name: "COBOL Insight",
    run: "解析",
    cancel: "中止",
    running: "解析中",
  },

  activity: {
    explorer: "エクスプローラー",
    rules: "ルール",
    label: "アクティビティバー",
  },

  sideBar: {
    explorerTitle: "エクスプローラー",
    rulesTitle: "ルール",
    label: "サイドバー",
    resize: "サイドバーの幅",
  },

  editor: {
    tabs: "エディタータブ",
    close: "閉じる",
    unsaved: "未保存の変更",
  },

  panel: {
    label: "パネル",
    problems: "指摘",
    output: "出力",
    resize: "パネルの高さ",
    close: "パネルを閉じる",
  },

  /** The two standing states every view that waits on a folder or a run shows. */
  empty: {
    noFolder: "資産フォルダを開いていません。",
    notAnalysed: "まだ解析していません。",
  },

  status: {
    label: "ステータスバー",
    findings: "指摘",
    unit: "件",
    caret: (line: number, column: number): string => `行 ${line}、列 ${column}`,
  },

  welcome: {
    selectFolder: "資産フォルダを開く",
    recent: "前回のフォルダ",
    shortcutHint: "コマンドパレットを開く",
  },

  explorer: {
    search: "名前で絞り込む",
    searchLabel: "資産の名前で絞り込む",
    typeLabel: "資産の種別で絞り込む",
    tree: "資産ツリー",
    loading: "走査しています…",
    noMatch: "絞り込みに一致する資産がありません。",
    error: "資産一覧を読めませんでした。",
    codepageUnknown: "文字コード不明",
    findingCount: (count: number): string => `指摘 ${count}件`,
  },

  assetType: {
    all: "すべての種別",
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
    loading: "検出しています…",
    noMatch: "絞り込みに一致する指摘がありません。",
    clean: "指摘はありません。",
    error: "指摘を読めませんでした。",
    search: "内容・ルール・資産で絞り込む",
    searchLabel: "指摘を内容・ルール・資産で絞り込む",
    hidden: (count: number): string => `しきい値未満の指摘 ${count}件を表示していません。`,
    columnSeverity: "重大度",
    columnRule: "ルール",
    columnMessage: "内容",
    columnFile: "資産",
    columnLine: "行",
    openInGraph: (file: string): string => `${file} を呼び出し関係図で開く`,
    selectOne: "指摘を選んでください。",
    /** Kept as the accessible name of the list of related lines; the visible heading is gone. */
    detailRelated: "関係する行",
    detailRule: "ルールの説明を開く",
    showFix: "修正案を開く",
    jumpTo: (file: string, line: number): string => `${file} の${line}行へ`,
  },

  source: {
    save: "保存時の検証",
  },

  output: {
    title: "出力",
    clear: "ログを消去",
  },

  sourceView: {
    loading: "本文を読み込んでいます…",
    // product-ui: ignore C6 the engine's own sentence carries the cause and the next step, and is rendered on the line beneath this headline.
    error: "本文を表示できませんでした。",
    detected: "自動判別",
    editor: "本文",
    readOnly: "読み取り専用",
    overflow: (bytes: number): string => `この行は${bytes}バイトあり、80桁の記録に収まりません。`,
  },

  copyExpansion: {
    expand: "展開を開く",
    collapse: "展開を畳む",
    toggleLabel: (name: string): string => `${name} の展開`,
    // product-ui: ignore C14 Monaco editor command title (not a rendered <button>); the checker buckets it as "button" only because the catalog key is literally "action".
    action: "カーソル行のCOPYの展開を切り替える",
    glyphHint: "このCOPYの展開を切り替えます。",
  },

  transpileView: {
    tab: "変換",
    language: "変換する言語",
    python: "Python",
    java: "Java",
    loading: "変換しています…",
    // product-ui: ignore C6 the engine's own reason is rendered on the line beneath this headline.
    error: "変換結果を取得できませんでした。",
    empty: "この資産の変換結果はありません。",
    noMap: "行の対応が記録されていません。",
  },

  save: {
    savedWithErrors: (path: string, count: number): string =>
      `「${path}」を保存しました。保存時の検証で${count}件の指摘が出ています。`,
    failed: (path: string, reason: string): string =>
      `「${path}」を保存できませんでした。${reason}`,
    nothingToSave: "保存していない変更はありません。",
    unsavedRemain: (count: number): string => `${count}件の資産が未保存のままです。`,
    dirtyBeforeFolderChange:
      "保存していない変更があります。保存するか変更を破棄してから、フォルダを開いてください。",
    conflictTitle: "原本が書き換わっています",
    conflictBody: (path: string): string =>
      `「${path}」は、開いたあとにこのツールの外で書き換わりました。上書きすると、その変更は失われます。再読み込みすると、保存していない変更は失われます。`,
    overwrite: "上書きする",
    reload: "再読み込みする",
    showDiff: "差分を見る",
    conflictCancel: "キャンセル",
    diskLabel: "原本",
    draftLabel: "編集中",
  },

  quickFix: {
    showFix: "この指摘の修正案を開く",
  },

  fixView: {
    title: "修正案",
    loading: "修正案を作っています…",
    // product-ui: ignore C6 the engine's own reason is rendered on the line beneath this headline.
    error: "修正案を取得できませんでした。",
    empty: "この資産に対する修正案はありません。",
    original: "原本",
    fixed: "修正案",
    apply: "フォルダ全体の修正案を書き出す",
    applied: (dir: string, count: number): string =>
      `「${dir}」へ${count}件の修正案を書き出しました。`,
    outDirInside: "修正案の出力先が資産フォルダの中にあります。設定で出力先を変えてください。",
  },

  graph: {
    title: "呼び出し関係図",
    loading: "呼び出し関係を読み込んでいます…",
    laying: "配置を計算しています…",
    error: "呼び出し関係を読めませんでした。",
    noNodes: "呼び出し関係のノードがありません。JCLかCOBOLを含む資産フォルダを解析してください。",
    canvas: "呼び出し関係図。キーボードでは実行順の一覧から選びます。",
    search: "ノードを名前で絞り込む",
    depth: "深さ",
    depthLabel: "起点からたどる深さ",
    clearFocus: "起点を解除",
    zoomIn: "拡大",
    zoomOut: "縮小",
    fit: "全体を表示",
    kinds: "ノードの種別で絞り込む",
    legend: "凡例",
    nodeCount: (visible: number, total: number): string => `ノード ${visible}/${total}`,
    expand: (label: string, open: boolean): string =>
      `${label} の下位を${open ? "畳む" : "開く"}`,
    cyclic: "巡回のためここで止めています",
    detail: "ノードの情報",
    incoming: "このノードへの辺",
    outgoing: "このノードからの辺",
    noEdges: "辺はありません。",
    openSource: "資産を開く",
    columnSeq: "実行順",
    columnPeer: "相手",
    columnKind: "種別",
    columnLine: "行",
    nodeKind: {
      JOB: "ジョブ",
      STEP: "ステップ",
      PROGRAM: "プログラム",
      PARAGRAPH: "段落",
      DATASET: "データセット",
      DB2_TABLE: "Db2表",
      TRANSACTION: "トランザクション",
      BMS_MAP: "BMSマップ",
      EXTERNAL_UTILITY: "外部ユーティリティ",
      UNRESOLVED: "未解決",
      UNANALYZABLE: "解析不能",
    },
    edgeKind: {
      EXECUTION: "実行（EXEC PGM）",
      CALL: "呼び出し（CALL・XCTL・LINK）",
      REFERENCE: "参照（データセット・Db2表）",
      // product-ui: ignore C14 graph legend label (not a rendered <button>); the checker buckets it as "button" only because "action" is a substring of "TRANSACTION".
      TRANSACTION_TRANSITION: "トランザクション遷移",
      MAP_REFERENCE: "BMSマップ参照",
    },
    resolution: {
      CONSTANT: "定数由来",
      DATAFLOW: "データフロー由来",
      UNRESOLVED: "未解決",
    },
    traceKind: {
      job: "ジョブ",
      step: "ステップ",
      program: "プログラム",
      paragraph: "段落",
      perform: "PERFORM",
      goto: "GOTO",
      unresolved: "未解決",
    },
  },

  report: {
    title: "レポート",
    generateHtml: "HTMLを生成",
    generateText: "テキストを生成",
    export: "書き出す",
    exportLabel: "別の場所へ書き出す",
    generating: "レポートを生成しています…",
    failed: (reason: string): string => `レポートを生成できませんでした。${reason}`,
    preview: "レポートの内容",
    path: "出力先",
    saved: (path: string): string => `「${path}」へ書き出しました。`,
  },

  run: {
    scan: "走査",
    lint: "指摘の検出",
    sqlLint: "SQL指摘の検出",
    started: "解析を開始しました。",
    cancelled: "解析を中止しました（完了した分の結果は残ります）。",
    stageStarted: (stage: string): string => `${stage}を開始しました。`,
    stageDone: (stage: string, count: number): string => `${stage}を終えました（${count}件）。`,
    stageFailed: (stage: string): string => `${stage}を完了できませんでした。`,
    scanDone: (count: number): string => `走査を終えました（資産 ${count}件）。`,
    stageCancelled: (stage: string): string => `${stage}を中止しました。`,
    noFolder: "先に資産フォルダを開いてください。",
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
    selectFolder: "資産フォルダを開く",
    run: "解析",
    cancel: "解析を中止する",
    toggleSideBar: "サイドバーの表示を切り替える",
    togglePanel: "パネルの表示を切り替える",
    showExplorer: "エクスプローラーを開く",
    showRules: "ルールを開く",
    showProblems: "指摘を開く",
    showOutput: "出力を開く",
    showGraph: "呼び出し関係図を開く",
    showReport: "レポートを開く",
    showSettings: "設定を開く",
    showTranspile: "この資産の変換結果を開く",
    reopenWithEncoding: "文字コードを指定して開き直す",
    discard: "変更を破棄する",
    toggleRule: "このルールの有効・無効を切り替える",
    validateCustomRules: "利用者定義ルールを検証する",
    saveCustomRules: "利用者定義ルールを保存する",
    categoryRules: "ルール",
    closeTab: "タブを閉じる",
    nextTab: "次のタブへ",
    previousTab: "前のタブへ",
    save: "保存する",
    saveAll: "すべて保存する",
  },

  modal: {
    confirmDiscardTitle: "保存していない変更があります",
    discardConfirm: "破棄する",
    discard: "破棄して閉じる",
    saveAndClose: "保存して閉じる",
    keep: "編集を続ける",
    close: "閉じる",
  },

  toast: {
    close: "通知を閉じる",
  },

  rules: {
    search: "ルールを絞り込む",
    searchLabel: "ルールをID・名前・概要で絞り込む",
    empty: "ルールを読み込んでいます…",
    loadFailed: "ルールを読み込めませんでした。",
    noMatch: "絞り込みに一致するルールがありません。",
    enableGroup: "この分類をすべて有効",
    disableGroup: "この分類をすべて無効",
    enabledLabel: (id: string): string => `${id} の有効・無効`,
    builtin: "組み込み",
    user: "利用者定義",
    hasFix: "修正案あり",
    openCustom: "利用者定義ルールを編集",
    ruleErrors: "ルール設定に問題があります。",
    severityLabel: (id: string): string => `${id} の重大度`,
    severityDefault: "既定",
    detailSummary: "検出する内容",
    detailRationale: "なぜ問題か",
    detailDetection: "検出条件",
    detailRemedy: "直し方",
    detailBad: "該当する例",
    detailGood: "直した例",
    detailCommands: "実行するコマンド",
    detailTargets: "対象の資産",
    detailUnknown: "このルールは見つかりません。",
  },

  customRules: {
    title: "利用者定義ルール",
    paneForm: "フォーム",
    paneRaw: "JSON",
    paneLabel: "編集方法",
    rawLabel: "custom 配列のJSON",
    rawInvalid: (detail: string): string => `JSONとして読めません。${detail}`,
    rawNotArray: "customはルール定義の配列で書きます。",
    rawBlocked: "JSONを直せるまでフォームへは戻れません。",
    add: "ルールを追加",
    remove: "このルールを削除する",
    validate: "検証",
    save: "保存",
    validationOk: "解析エンジンはこの内容を受け付けました。",
    validationFailed: "解析エンジンはこの内容を受け付けませんでした。",
    fieldId: "ID",
    fieldName: "名前",
    fieldCategory: "分類",
    fieldSummary: "概要",
    fieldSeverity: "重大度",
    fieldCommands: "実行するコマンド",
    fieldTargets: "対象の資産",
    fieldMessage: "指摘の文言",
    fieldRationale: "なぜ問題か",
    fieldRemedy: "直し方",
    fieldMatchKind: "検出条件",
    fieldRegex: "正規表現",
    fieldIgnoreCase: "大文字と小文字を区別しない",
    fieldArea: "走査する範囲",
    fieldExcludeRegex: "除外する正規表現",
    fieldVerb: "対象の動詞",
    fieldMissingClause: "欠けていると検出する句",
    fieldInParagraph: "対象の段落名（正規表現）",
    fieldAfterVerb: "起点の動詞",
    fieldAfterTextRegex: "起点の文の正規表現",
    fieldDataItem: "検査するデータ項目",
    fieldScope: "追う範囲",
    fieldOnEveryPath: "全経路での検査を求める",
    // Example values. Each one shows its field's vocabulary and, for a list, its separator.
    messagePlaceholder: "${match} が見つかりました",
    verbPlaceholder: "READ, WRITE",
    missingClausePlaceholder: "AT END, INVALID KEY",
    dataItemPlaceholder: "WS-STATUS",
    matchKind: {
      line: "行の正規表現",
      statement: "文と句",
      "checked-after": "文の後の検査",
    },
    area: {
      programArea: "本体（8〜72桁・注記行を除く）",
      wholeLine: "行全体",
    },
    scope: {
      untilNextMatchingStatement: "同じ動詞の次の文まで",
      untilParagraphEnd: "段落の終わりまで",
      untilProgramEnd: "プログラムの終わりまで",
    },
    problem: {
      idFormat: "IDはUで始め、英数字・ハイフン・下線を1〜15文字続けます。",
      idDuplicate: "このIDは他のルールで使われています。",
      nameRequired: REQUIRED,
      messageRequired: REQUIRED,
      regexRequired: REQUIRED,
      verbRequired: REQUIRED,
      afterVerbRequired: REQUIRED,
      dataItemRequired: REQUIRED,
    },
  },

  settings: {
    title: "設定",
    theme: "配色テーマ",
    themeSystem: "OSに合わせる",
    themeDark: "ダーク",
    themeLight: "ライト",
    encoding: "既定の文字コード",
    encodingAuto: "自動判別",
    copybookPaths: "コピー句の探索パス",
    copybookAdd: "パスを追加",
    copybookRemove: "このパスを削除",
    copybookPlaceholder: "C:\\assets\\copybook",
    copybookMissing: "このフォルダは見つかりません。",
    threshold: "指摘の重大度しきい値",
    fixOutDir: "修正案の出力先",
    fixOutDirInside: "資産フォルダの外を指定してください。",
    browse: "参照",
  },

  import: {
    open: "端末から取り込む",
    title: "端末からの取り込み",
    paste: "端末から貼り付けた本文",
    kind: "資産の種別",
    destDir: "保存先",
    destDirPlaceholder: "資産フォルダの直下",
    fileName: "ファイル名",
    fileNamePlaceholder: "SYK001.cbl",
    fileNameInvalid: 'パス区切りと記号 \\ / : * ? " < > | は使えません。',
    destination: (relPath: string): string => `取り込み先: ${relPath}`,
    columns: "取り込む桁",
    columnFrom: "開始桁",
    columnTo: "終了桁",
    columnsInvalid: "終了桁は開始桁以上にしてください。",
    preview: "切り出した本文",
    lineCount: (count: number): string => `${count}行`,
    save: "取り込む",
    saving: "取り込んでいます…",
    cancel: "キャンセル",
    exists: (relPath: string): string => `「${relPath}」は既にあります。`,
    saved: (relPath: string, count: number): string =>
      `「${relPath}」を取り込みました（${count}行）。もう一度解析すると一覧に出ます。`,
    overwrite: "上書きする",
  },
} as const;

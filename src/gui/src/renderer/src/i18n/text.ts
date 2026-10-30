/**
 * Every user-visible string in the renderer. The product is Japanese, so the values are Japanese and
 * the keys are English; keeping them all here lets a later wording pass edit one file.
 *
 * Rule names, categories and prose are deliberately absent: the engine is their only source and the
 * screens render what `rules --json` returns.
 */

/** The note under an empty required field. The field's label sits right above it, so it is not repeated. */
const REQUIRED = "必須です。";

/** The analyses a rule runs under, by the engine's name for each (core/rule/Command.java). */
const RULE_COMMANDS: Readonly<Record<string, string>> = {
  LINT: "指摘の検出",
  SQL_LINT: "SQLの指摘の検出",
  FIX: "修正案の作成",
};

/** The asset kinds a rule examines, by the engine's name for each. */
const RULE_TARGETS: Readonly<Record<string, string>> = {
  COBOL: "COBOL",
  COPYBOOK: "コピー句",
  JCL: "JCL",
  BMS: "BMS",
};

/** The labels of the flow kinds, which the source view's legend and the call graph's trace tree share. */
const PERFORM_LABEL = "PERFORM";
const GOTO_LABEL = "GO TO";
const FALLTHROUGH_LABEL = "次の段落";
const CALL_LABEL = "CALL";
const TRACE_LABEL = "トレース";
const SELECT_FOLDER_LABEL = "資産フォルダを開く";
const lineLabel = (line: number): string => `${line}行目`;

/**
 * What a view drawn from the last scan says while a write waits for the scan after it, and once that
 * scan failed or was stopped. The source pane, the call graph and the COPY expansion share them.
 */
const STRUCTURE_READING = "保存した内容を読み取っています…";
const STRUCTURE_UNREAD = "保存した内容は未反映です。";

export const text = {
  app: {
    name: "COBOL Insight",
    run: "解析",
    /** What the run button will analyse, named beside it. */
    runTarget: (path: string, kind: "file" | "folder"): string =>
      kind === "folder" ? `解析: ${path}（フォルダ）` : `解析: ${path}`,
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
    failed: "この画面を表示できませんでした。",
    viewFailed: "この表示を作れませんでした。",
    failedReason: "エラーの内容",
    retry: "もう一度表示",
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
    codepageAfterSave: "文字コードは、変更を保存するか破棄したあとで切り替えられます。",
  },

  welcome: {
    selectFolder: SELECT_FOLDER_LABEL,
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
    sql: "SQLスクリプト",
    other: "その他",
  },

  severity: {
    high: "高",
    medium: "中",
    low: "低",
    warning: "参考",
  },

  problems: {
    title: "指摘",
    loading: "検出しています…",
    noMatch: "絞り込みに一致する指摘がありません。",
    clean: "指摘はありません。",
    error: "指摘を読めませんでした。",
    search: "内容・ルール・資産で絞り込む",
    // product-ui: ignore C14 accessible name of the filter box, read by assistive technology only; it names the three fields it searches.
    searchLabel: "指摘を内容・ルール・資産で絞り込む",
    hidden: (count: number): string => `しきい値未満の指摘 ${count}件を表示していません。`,
    staleRules: "一覧は変更前のルールによる結果です。",
    columnSeverity: "重大度",
    columnRule: "ルール",
    columnMessage: "内容",
    columnFile: "資産",
    columnLine: "行",
    openInGraph: (file: string): string => `${file} を呼び出し関係図で開く`,
    /** Kept as the accessible name of the list of related lines; the visible heading is gone. */
    detailRelated: "関係する行",
    detailRule: "ルールの説明を開く",
    showFix: "修正案を開く",
    line: lineLabel,
    jumpTo: (file: string, line: number): string => `${file} の${line}行目へ`,
  },

  /**
   * The engine's own finding ids. They are diagnostics of the analysis itself, so the catalogue
   * of rules does not list them and their names live here.
   */
  diagnostic: {
    "parse-failure": "構文解析の失敗",
    "decode-failure": "復号の失敗",
    "jcl-syntax": "読み取れなかったJCL文",
    "jcl-directive": "読み飛ばした指示行",
    "sql-syntax": "読み切れなかったSQL文",
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
    pane: "段落",
    paragraphs: "段落一覧",
    trace: TRACE_LABEL,
    peek: "段落の本文",
    reveal: "本文に移動",
    // product-ui: ignore C14 Monaco editor command title, listed in the editor's command list and never drawn as a button.
    peekCommand: "カーソル位置の段落を表示",
    noParagraphs: "段落はありません。",
    flowLoading: "段落の流れを読み込んでいます…",
    // product-ui: ignore C6 the engine's own reason is rendered on the line beneath this headline.
    flowError: "段落の流れを読めませんでした。",
    flowStale: STRUCTURE_READING,
    flowUnread: STRUCTURE_UNREAD,
    rootMissing: "この段落は直近の解析結果にありません。",
    peekHeight: "段落の本文の高さ",
    graphHeight: "トレースの図の高さ",
    truncated: (limit: number): string => `表示は${limit}行までです。`,
    flowCanvas: "トレースの図。キーボードではトレースの一覧から選びます。",
    flowKinds: "線の種別",
    flowKind: {
      PERFORM: PERFORM_LABEL,
      GOTO: GOTO_LABEL,
      FALLTHROUGH: FALLTHROUGH_LABEL,
      CALL: CALL_LABEL,
    },
  },

  copyExpansion: {
    expand: "展開",
    collapse: "折りたたむ",
    toggleLabel: (name: string): string => `${name} の展開`,
    // product-ui: ignore C14 Monaco editor command title, listed in the editor's command list; the checker buckets it as "button" only because the catalog key is literally "action".
    action: "カーソル行のCOPYの展開を切り替える",
    structureReading: STRUCTURE_READING,
    structureUnread: STRUCTURE_UNREAD,
  },

  transpileView: {
    tab: "変換",
    language: "変換先の言語",
    python: "Python",
    java: "Java",
    loading: "変換しています…",
    // product-ui: ignore C6 the engine's own reason is rendered on the line beneath this headline.
    error: "変換結果を取得できませんでした。",
    empty: "この資産の変換結果はありません。",
    noMap: "行の対応が記録されていません。",
    beforeSave: "保存前の内容から変換した結果です。",
  },

  save: {
    savedWithErrors: (path: string, count: number): string =>
      `「${path}」を保存しました。保存時の検証で${count}件の指摘が出ています。`,
    failed: (path: string, reason: string): string =>
      `「${path}」を保存できませんでした。${reason}`,
    checkFailed: (path: string, reason: string): string =>
      `「${path}」を保存しました。保存後の検証を完了できませんでした。${reason}`,
    nothingToSave: "保存していない変更はありません。",
    unsavedRemain: (count: number): string => `${count}件の資産が未保存のままです。`,
    dirtyBeforeFolderChange:
      "保存していない変更があるため、フォルダを開けません。すべて保存するか、変更を破棄してください。",
    conflictTitle: "原本が書き換わっています",
    conflictBody: (path: string): string =>
      `「${path}」は、開いたあとにこのツールの外で書き換わりました。上書きすると、その変更は失われます。再読み込みすると、保存していない変更は失われます。`,
    overwrite: "上書き",
    reload: "再読み込み",
    showDiff: "差分を見る",
    conflictCancel: "キャンセル",
    /** The name of the comparison in the conflict dialog: the original on the left, the draft on the right. */
    compareLabel: "原本 / 編集中",
  },

  fixView: {
    title: "修正案",
    loading: "修正案を作っています…",
    // product-ui: ignore C6 the engine's own reason is rendered on the line beneath this headline.
    error: "修正案を取得できませんでした。",
    empty: "この資産に対する修正案はありません。",
    original: "原本",
    fixed: "修正案",
    apply: "資産フォルダ全体の修正案を書き出す",
    applyWaits: "解析が終わると書き出せます。",
    beforeSave: "保存前の内容から作った修正案です。",
    applied: (dir: string, count: number): string =>
      `「${dir}」へ修正した資産 ${count}件を書き出しました。`,
    exportFailed: (reason: string): string => `修正案を書き出せませんでした。${reason}`,
    outDirInside: "修正案の出力先が資産フォルダの中にあります。設定で出力先を変えてください。",
  },

  graph: {
    title: "呼び出し関係図",
    loading: "呼び出し関係を読み込んでいます…",
    rereading: "呼び出し関係を読み直しています…",
    structureReading: STRUCTURE_READING,
    structureUnread: STRUCTURE_UNREAD,
    laying: "配置を計算しています…",
    error: "呼び出し関係を読めませんでした。",
    noNodes: "呼び出し関係のノードがありません。",
    canvas: "呼び出し関係図。キーボードではトレースの一覧から選びます。",
    search: "名前で絞り込む",
    depth: "深さ",
    depthLabel: "起点からたどる深さ",
    clearFocus: (label: string): string => `起点 ${label} を解除`,
    clearFocusNone: "起点を解除",
    zoomIn: "拡大",
    zoomOut: "縮小",
    fit: "全体を表示",
    kinds: "ノードの種別で絞り込む",
    legend: "凡例",
    nodeCount: (visible: number, total: number): string => `表示 ${visible}/${total}`,
    expand: (label: string, open: boolean): string =>
      `${label} の下位を${open ? "折りたたむ" : "開く"}`,
    cyclic: "巡回",
    detail: "ノードの情報",
    incoming: "呼び出し元",
    outgoing: "呼び出し先",
    noEdges: "呼び出しはありません。",
    selectOne: "トレースか図でノードを選んでください。",
    openSource: "資産を開く",
    traceTitle: TRACE_LABEL,
    columnSeq: "実行順",
    line: lineLabel,
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
      CALL: "呼び出し（CALL・LINK）",
      REFERENCE: "参照（データセット・Db2表・BINDの対象）",
      // product-ui: ignore C14 graph legend label, drawn as plain text; the checker buckets it as "button" only because "action" is a substring of "TRANSACTION".
      TRANSACTION_TRANSITION: "トランザクション遷移（XCTL・START・RETURN TRANSID）",
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
      perform: PERFORM_LABEL,
      goto: GOTO_LABEL,
      unresolved: "未解決",
      division: "部",
      declaratives: "宣言部分",
      section: "節",
      fallthrough: FALLTHROUGH_LABEL,
      call: CALL_LABEL,
      xctl: "XCTL",
    },
  },

  report: {
    title: "レポート",
    generateHtml: "HTMLで作成",
    generateText: "テキストで作成",
    export: "別の場所へ書き出す",
    generating: "レポートを作成しています…",
    waiting: "解析が終わるのを待っています…",
    outdated: "作成後に保存または解析した内容は、このレポートに入っていません。",
    failed: (reason: string): string => `レポートを作成できませんでした。${reason}`,
    preview: "レポートの内容",
    /** Shown when a scoped run came after the last whole-folder run, which the report is built from. */
    scopedSinceWholeRun: "範囲を絞った直近の解析結果は、レポートに入っていません。",
    pathAt: (path: string): string => `出力先: ${path}`,
    saved: (path: string): string => `「${path}」へ書き出しました。`,
  },

  run: {
    scan: "走査",
    lint: "指摘の検出",
    started: "解析を開始しました。",
    startedScope: (scope: string): string => `解析を開始しました（対象: ${scope}）。`,
    cancelled: "解析を中止しました。",
    stageStarted: (stage: string): string => `${stage}を開始しました。`,
    stageDone: (stage: string, count: number): string => `${stage}を終えました（${count}件）。`,
    stageFailed: (stage: string): string => `${stage}を完了できませんでした。`,
    scanDone: (count: number): string => `走査を終えました（資産 ${count}件）。`,
    stageCancelled: (stage: string): string => `${stage}を中止しました。`,
    noFolder: "先に資産フォルダを開いてください。",
    folderWhileRunning: "解析の実行中はフォルダを開けません。解析が終わってから開いてください。",
    noSelection: "解析する対象をエクスプローラーで選んでください。",
    /** A copybook is analysed through the programs that copy it, so it cannot be a scope of its own. */
    copybookSelection: "コピー句は単独では解析できません。コピー句を使うプログラムを選んでください。",
    engineFailed: "解析エンジンが途中で終了しました。もう一度解析してください。",
    scopeFailed: (scope: string, reason: string): string =>
      `「${scope}」を解析できませんでした。${reason}`,
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
    selectFolder: SELECT_FOLDER_LABEL,
    selectFolderAt: (path: string): string => `${SELECT_FOLDER_LABEL}（${path}）`,
    run: "解析",
    runAll: "資産フォルダ全体を解析",
    cancel: "解析を中止",
    toggleSideBar: "サイドバーの表示を切り替える",
    togglePanel: "パネルの表示を切り替える",
    showExplorer: "エクスプローラーを開く",
    showRules: "ルールを開く",
    showProblems: "指摘を開く",
    showOutput: "出力を開く",
    showGraph: "呼び出し関係図を開く",
    showReport: "レポートを開く",
    showSettings: "設定を開く",
    showTranspile: "変換を開く",
    reopenWithEncoding: "文字コードを指定して開き直す",
    closeTab: "タブを閉じる",
    nextTab: "次のタブへ",
    previousTab: "前のタブへ",
    save: "保存",
    saveAll: "すべて保存",
  },

  modal: {
    confirmDiscardTitle: "保存していない変更があります",
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
    // product-ui: ignore C14 accessible name of the filter box, read by assistive technology only; it names the three fields it searches.
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
    detailCommands: "実行する解析",
    detailTargets: "対象の資産",
    detailUnknown: "このルールは見つかりません。",
    /** A name the engine adds later is shown as the engine writes it. */
    commandName: (command: string): string => RULE_COMMANDS[command] ?? command,
    targetName: (target: string): string => RULE_TARGETS[target] ?? target,
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
    jsonOnly: "このルールはフォームで表せない内容を含みます。JSONで編集してください。",
    add: "ルールを追加",
    remove: "このルールを削除",
    validate: "検証",
    save: "保存",
    validationOk: "解析エンジンはこの内容を受け付けました。",
    validationFailed: "解析エンジンはこの内容を受け付けませんでした。",
    fieldId: "ID",
    fieldName: "名前",
    fieldCategory: "分類",
    fieldSummary: "概要",
    fieldSeverity: "重大度",
    fieldCommands: "実行する解析",
    fieldTargets: "対象の資産",
    fieldMessage: "指摘の文言",
    fieldRationale: "なぜ問題か",
    fieldRemedy: "直し方",
    fieldMatchKind: "検出条件",
    fieldRegex: "正規表現",
    // product-ui: ignore C14 a shorter label changes the option's meaning.
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
    /** One problem as the JSON pane lists it: which rule, which field, what is wrong. */
    problemAt: (rule: string, field: string, note: string): string => `${rule}の${field}: ${note}`,
    ruleAt: (ordinal: number): string => `${ordinal}番目のルール`,
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
    threshold: "一覧に表示する最低の重大度",
    fixOutDir: "修正案の出力先",
    fixOutDirInside: "資産フォルダの外を指定してください。",
    browse: "フォルダを選択",
  },

  import: {
    open: "端末から貼り付ける",
    title: "端末からの貼り付け",
    paste: "端末から貼り付けた本文",
    destDir: "保存先フォルダ",
    destDirPlaceholder: "資産フォルダの直下",
    fileName: "ファイル名",
    destDirInvalid:
      '資産フォルダの中のフォルダを指定してください。フォルダ名に、記号 : * ? " < > |、制御文字、先頭と末尾のピリオド、256文字以上の名前、装置名（CON、PRN、AUX、NUL、COM1、LPT1 など。拡張子を付けた名前も同じ）は使えません。',
    fileNamePlaceholder: "例: PGM001.cbl",
    fileNameRequired: REQUIRED,
    fileNameInvalid:
      '記号 \\ / : * ? " < > |、制御文字、先頭と末尾のピリオド、256文字以上の名前、装置名（CON、PRN、AUX、NUL、COM1、LPT1 など。拡張子を付けた名前も同じ）は使えません。',
    destination: (relPath: string): string => `保存先: ${relPath}`,
    columns: "保存する桁",
    columnFrom: "開始桁",
    columnTo: "終了桁",
    columnsInvalid: "終了桁は開始桁以上にしてください。",
    columnsRequired: (least: number, most: number): string =>
      `開始桁と終了桁を${least}〜${most}の数字で入力してください。`,
    kind: "画面の種類",
    kindAuto: "自動",
    kindName: {
      "ispf-edit": "ISPFエディター",
      "ispf-browse": "ISPFブラウズ",
      seu: "SEU",
    },
    kindManual: "桁を指定",
    read: (kind: string, chrome: number, duplicates: number): string =>
      `${kind}の画面として読み取り、本文以外の${chrome}行と重複した${duplicates}行を除きました。`,
    readPlain:
      "端末の画面として読み取れませんでした。指定した桁を保存します。見出し行のない画面は、画面の種類を選ぶと読み取れます。",
    conflicts: (count: number): string =>
      `重なる行で内容の違う行が${count}件あります。後に貼り付けた内容を採用しました。`,
    hidden: (count: number): string =>
      `非表示の${count}行は貼り付けた本文に含まれていません。画面に表示してから貼り付け直してください。`,
    preview: "切り出した本文",
    lineCount: (count: number): string => `${count}行`,
    save: "保存",
    saving: "保存しています…",
    cancel: "キャンセル",
    exists: (relPath: string): string =>
      `「${relPath}」は既にあります。上書きすると、いまの内容は失われます。`,
    saved: (relPath: string, count: number): string =>
      `「${relPath}」を保存しました（${count}行）。`,
    overwrite: "上書き",
  },
} as const;

import { describe, it, expect } from "vitest";
import { appReducer, restorePaneSizes } from "./appReducer";
import { SPLIT_PANES, initialState, type AppState, type SplitPaneId } from "./appState";
import { MANUAL_ENCODING_OPTIONS } from "../screens/explorer/assetView";
import { SAMPLE_INVENTORY } from "../screens/explorer/fixtures";
import { SAMPLE_FINDINGS, SAMPLE_SQL_FINDINGS } from "../screens/findings/fixtures";

/** 特定フィールドを上書きした状態を作る補助。 */
function withState(overrides: Partial<AppState>): AppState {
  return { ...initialState, ...overrides };
}

describe("appReducer", () => {
  it("初期状態は資産エクスプローラー・空モードで始まる", () => {
    expect(initialState.screen).toBe("explorer");
    expect(initialState.mode).toBe("empty");
    expect(initialState.toastMsg).toBeNull();
  });

  describe("NAV(画面ルーティング)", () => {
    it("screen を切り替える", () => {
      const next = appReducer(initialState, { type: "NAV", screen: "report" });
      expect(next.screen).toBe("report");
    });

    it("mode やフィルタは変えない", () => {
      const seed = withState({ mode: "results", findingText: "SQLCODE" });
      const next = appReducer(seed, { type: "NAV", screen: "findings" });
      expect(next.mode).toBe("results");
      expect(next.findingText).toBe("SQLCODE");
    });
  });

  describe("SET_MODE(4状態切替)", () => {
    it.each(["empty", "running", "results", "error"] as const)("mode を %s へ設定する", (mode) => {
      const next = appReducer(initialState, { type: "SET_MODE", mode });
      expect(next.mode).toBe(mode);
    });

    it("選択ノードとトーストをクリアする(design setMode)", () => {
      const seed = withState({ selectedNode: "P1", toastMsg: "残存トースト" });
      const next = appReducer(seed, { type: "SET_MODE", mode: "results" });
      expect(next.selectedNode).toBeNull();
      expect(next.toastMsg).toBeNull();
    });
  });

  describe("実行ライフサイクル", () => {
    it("START_RUN で running・第1段にする", () => {
      const next = appReducer(initialState, { type: "START_RUN" });
      expect(next.mode).toBe("running");
      expect(next.runStage).toBe(1);
    });

    it("SET_RUN_STAGE で段を進める", () => {
      const running = appReducer(initialState, { type: "START_RUN" });
      const next = appReducer(running, { type: "SET_RUN_STAGE", stage: 3 });
      expect(next.runStage).toBe(3);
    });

    it("FINISH_RUN で results にし、トーストを反映する", () => {
      const running = appReducer(initialState, { type: "START_RUN" });
      const next = appReducer(running, { type: "FINISH_RUN", toast: "解析が完了しました" });
      expect(next.mode).toBe("results");
      expect(next.toastMsg).toBe("解析が完了しました");
    });

    it("FINISH_RUN の failed で error モードにする", () => {
      const running = appReducer(initialState, { type: "START_RUN" });
      const next = appReducer(running, { type: "FINISH_RUN", failed: true });
      expect(next.mode).toBe("error");
    });

    it("START_RUN は前回の資産一覧・指摘・SQL指摘を破棄する(古い結果を残さない)", () => {
      const seed = withState({
        inventory: { status: "ready", items: SAMPLE_INVENTORY },
        findings: { status: "ready", items: SAMPLE_FINDINGS },
        sqlAdvice: { status: "error", message: "起動失敗" },
      });
      const next = appReducer(seed, { type: "START_RUN" });
      expect(next.inventory).toEqual({ status: "none" });
      expect(next.findings).toEqual({ status: "none" });
      expect(next.sqlAdvice).toEqual({ status: "none" });
    });

    it("CANCEL_RUN で results に戻し、既定のキャンセルトーストを出す", () => {
      const running = appReducer(initialState, { type: "START_RUN" });
      const next = appReducer(running, { type: "CANCEL_RUN" });
      expect(next.mode).toBe("results");
      expect(next.toastMsg).toContain("キャンセル");
    });
  });

  describe("JUMP(画面横断ジャンプ、design の jump)", () => {
    it("行付きジャンプでソースビューアへ遷移し、ジャンプ文言を組む", () => {
      const seed = withState({ screen: "findings", copybookOpen: true, linkedCobolLines: [85], linkedTranspileLines: [3] });
      const next = appReducer(seed, { type: "JUMP", file: "SYK001.cbl", line: 85, from: "指摘一覧" });
      expect(next.screen).toBe("viewer");
      expect(next.sourceFile).toBe("SYK001.cbl");
      expect(next.sourceLine).toBe(85);
      expect(next.sourceFrom).toBe("指摘一覧 から SYK001.cbl:85 へジャンプ");
      expect(next.copybookOpen).toBe(false);
      expect(next.linkedCobolLines).toEqual([]);
      expect(next.linkedTranspileLines).toEqual([]);
    });

    it("stay を立てると画面を移らず、ファイルと行だけを移す", () => {
      const seed = withState({ screen: "findings" });
      const next = appReducer(seed, {
        type: "JUMP",
        file: "SYK001.cbl",
        line: 85,
        from: "指摘一覧",
        stay: true,
      });
      expect(next.screen).toBe("findings");
      expect(next.sourceFile).toBe("SYK001.cbl");
      expect(next.sourceLine).toBe(85);
      expect(next.sourceFrom).toBe("指摘一覧 から SYK001.cbl:85 へジャンプ");
    });

    it("stay を立てなければ従来どおりソースビューアへ移る", () => {
      const seed = withState({ screen: "findings" });
      const next = appReducer(seed, {
        type: "JUMP",
        file: "SYK001.cbl",
        line: 85,
        from: "指摘一覧",
        stay: false,
      });
      expect(next.screen).toBe("viewer");
    });

    it("行なしジャンプは「を表示」文言にする", () => {
      const next = appReducer(initialState, { type: "JUMP", file: "SYK002.cbl", line: null, from: "呼出関係図" });
      expect(next.screen).toBe("viewer");
      expect(next.sourceLine).toBeNull();
      expect(next.sourceFrom).toBe("呼出関係図 から SYK002.cbl を表示");
    });
  });

  describe("ソースビューアの操作(design gvViewer)", () => {
    it("SET_SOURCE_FILE はジャンプ元・コピー句展開・対訳連携を初期化する", () => {
      const seed = withState({
        sourceFile: "cobol/SYK001.cbl",
        sourceLine: 85,
        sourceFrom: "指摘一覧 から cobol/SYK001.cbl:85 へジャンプ",
        copybookOpen: true,
        linkedCobolLines: [85],
        linkedTranspileLines: [40, 41],
      });
      const next = appReducer(seed, { type: "SET_SOURCE_FILE", file: "cobol/SYK002.cbl" });
      expect(next.sourceFile).toBe("cobol/SYK002.cbl");
      expect(next.sourceLine).toBeNull();
      expect(next.sourceFrom).toBeNull();
      expect(next.copybookOpen).toBe(false);
      expect(next.linkedCobolLines).toEqual([]);
      expect(next.linkedTranspileLines).toEqual([]);
    });

    it("SET_SOURCE_LANG は言語を切り替え、生成行の連携を捨てる", () => {
      const seed = withState({ linkedCobolLines: [20], linkedTranspileLines: [40] });
      const next = appReducer(seed, { type: "SET_SOURCE_LANG", lang: "java" });
      expect(next.sourceLang).toBe("java");
      expect(next.linkedCobolLines).toEqual([]);
      expect(next.linkedTranspileLines).toEqual([]);
    });

    it("TOGGLE_COPYBOOK_OPEN はコピー句展開を開閉する", () => {
      const opened = appReducer(initialState, { type: "TOGGLE_COPYBOOK_OPEN" });
      expect(opened.copybookOpen).toBe(true);
      expect(appReducer(opened, { type: "TOGGLE_COPYBOOK_OPEN" }).copybookOpen).toBe(false);
    });

    it("SET_LINKED_LINES は両ペインの強調行を差し替える", () => {
      const next = appReducer(initialState, {
        type: "SET_LINKED_LINES",
        cobolLines: [20, 21],
        transpileLines: [40, 41, 42],
      });
      expect(next.linkedCobolLines).toEqual([20, 21]);
      expect(next.linkedTranspileLines).toEqual([40, 41, 42]);
    });
  });

  describe("トースト(design pop)", () => {
    it("SHOW_TOAST で文言を設定する", () => {
      const next = appReducer(initialState, { type: "SHOW_TOAST", message: "SVG を出力しました" });
      expect(next.toastMsg).toBe("SVG を出力しました");
    });

    it("DISMISS_TOAST で文言をクリアする", () => {
      const seed = withState({ toastMsg: "表示中" });
      const next = appReducer(seed, { type: "DISMISS_TOAST" });
      expect(next.toastMsg).toBeNull();
    });
  });

  describe("資産エクスプローラーのフィルタ・選択・文字コード", () => {
    it("SET_ASSET_SEARCH で名前フィルタを設定する", () => {
      const next = appReducer(initialState, { type: "SET_ASSET_SEARCH", value: "SYK" });
      expect(next.assetSearch).toBe("SYK");
    });

    it("SET_ASSET_TYPE で種別フィルタを設定する", () => {
      const next = appReducer(initialState, { type: "SET_ASSET_TYPE", value: "コピー句" });
      expect(next.assetType).toBe("コピー句");
    });

    it("SELECT_ASSET で選択中の資産パスを設定する", () => {
      const next = appReducer(initialState, { type: "SELECT_ASSET", path: "cobol/SYK001.cbl" });
      expect(next.selectedAsset).toBe("cobol/SYK001.cbl");
    });

    it("SET_ASSET_ENCODING で該当パスの手動指定を追加し、他のパスは保つ", () => {
      const seed = withState({ encodingSel: { "cobol/A.cbl": "手動: UTF-8" } });
      const next = appReducer(seed, { type: "SET_ASSET_ENCODING", path: "cobol/B.cbl", encoding: "手動: EBCDIC CP930" });
      expect(next.encodingSel).toEqual({
        "cobol/A.cbl": "手動: UTF-8",
        "cobol/B.cbl": "手動: EBCDIC CP930",
      });
    });
  });

  describe("SQL指摘のフィルタと選択(指摘一覧と同じく AppState に持つ)", () => {
    it("TOGGLE_SQL_SEVERITY で重大度を切り替え、他の重大度は保つ", () => {
      const next = appReducer(initialState, { type: "TOGGLE_SQL_SEVERITY", severity: "medium" });
      expect(next.sqlSeverity).toEqual({ high: true, medium: false, low: true, warning: true });
      expect(next.findingSeverity).toEqual(initialState.findingSeverity);
    });

    it("SET_SQL_RULE・SET_SQL_FILE・SET_SQL_TEXT・SET_SQL_SORT を保持する", () => {
      let next = appReducer(initialState, { type: "SET_SQL_RULE", value: "S002" });
      next = appReducer(next, { type: "SET_SQL_FILE", value: "cobol/SYK007.cbl" });
      next = appReducer(next, { type: "SET_SQL_TEXT", value: "SARGable" });
      next = appReducer(next, { type: "SET_SQL_SORT", sort: { column: "line", direction: "asc" } });
      expect(next.sqlRule).toBe("S002");
      expect(next.sqlFile).toBe("cobol/SYK007.cbl");
      expect(next.sqlText).toBe("SARGable");
      expect(next.sqlSort).toEqual({ column: "line", direction: "asc" });
      // 指摘一覧のフィルタ・ソートは独立に保たれる。
      expect(next.findingRule).toBe("all");
      expect(next.findingSort).toEqual({ column: "sev", direction: "asc" });
    });

    it("SET_FINDING_SORT・SELECT_FINDING を保持する(SQL指摘とは独立)", () => {
      const target = SAMPLE_FINDINGS[2];
      let next = appReducer(initialState, {
        type: "SET_FINDING_SORT",
        sort: { column: "rule", direction: "desc" },
      });
      next = appReducer(next, { type: "SELECT_FINDING", finding: target });
      expect(next.findingSort).toEqual({ column: "rule", direction: "desc" });
      expect(next.findingSelected).toBe(target);
      // SQL指摘のソート・選択は独立に保たれる。
      expect(next.sqlSort).toEqual({ column: "sev", direction: "asc" });
      expect(next.sqlSelected).toBeNull();
    });

    it("START_RUN は指摘一覧の選択も捨てる(古い成果物の指摘を残さない)", () => {
      const seed = withState({ findingSelected: SAMPLE_FINDINGS[0] });
      const next = appReducer(seed, { type: "START_RUN" });
      expect(next.findingSelected).toBeNull();
    });

    it("SELECT_SQL_ADVICE で詳細ペインの対象を保持する", () => {
      const target = SAMPLE_SQL_FINDINGS[2];
      const next = appReducer(initialState, { type: "SELECT_SQL_ADVICE", finding: target });
      expect(next.sqlSelected).toBe(target);
    });

    it("START_RUN は前回の選択を捨てる(古い成果物の指摘を残さない)", () => {
      const seed = withState({ sqlSelected: SAMPLE_SQL_FINDINGS[0] });
      const next = appReducer(seed, { type: "START_RUN" });
      expect(next.sqlSelected).toBeNull();
    });
  });

  describe("プロジェクトと解析結果(全画面共有)", () => {
    it("SET_PROJECT で入力フォルダを保持する", () => {
      const next = appReducer(initialState, { type: "SET_PROJECT", project: { inputDir: "C:\\資産" } });
      expect(next.project.inputDir).toBe("C:\\資産");
    });

    it("SET_PROJECT はコピー句検索パスも保持する", () => {
      const next = appReducer(initialState, {
        type: "SET_PROJECT",
        project: { copybookPaths: ["C:\\資産\\copybook"] },
      });
      expect(next.project.copybookPaths).toEqual(["C:\\資産\\copybook"]);
    });

    it("SET_PROJECT は指定しなかった項目を保つ", () => {
      const seed = withState({
        project: { inputDir: "C:\\資産", dbPath: "proj.db", copybookPaths: ["C:\\copy"] },
      });
      const next = appReducer(seed, { type: "SET_PROJECT", project: { inputDir: "D:\\別資産" } });
      expect(next.project).toEqual({
        inputDir: "D:\\別資産",
        dbPath: "proj.db",
        copybookPaths: ["C:\\copy"],
      });
    });

    it("SET_IMPORT は指定しなかった項目を保つ", () => {
      const seed = withState({ importText: "貼り付けた本文", importColumnFrom: 8 });
      const next = appReducer(seed, {
        type: "SET_IMPORT",
        patch: { importFileName: "SYK001" },
      });
      expect(next.importText).toBe("貼り付けた本文");
      expect(next.importColumnFrom).toBe(8);
      expect(next.importFileName).toBe("SYK001");
    });

    it("SET_IMPORT は undefined を渡された項目を現在値のまま残す", () => {
      const seed = withState({ importText: "貼り付けた本文" });
      const next = appReducer(seed, {
        type: "SET_IMPORT",
        patch: { importText: undefined, importFileName: "SYK001" },
      });
      expect(next.importText).toBe("貼り付けた本文");
      expect(next.importFileName).toBe("SYK001");
    });

    it("SET_INVENTORY(ready)で資産一覧と DB パスを保持する", () => {
      const next = appReducer(initialState, {
        type: "SET_INVENTORY",
        result: { status: "ready", items: SAMPLE_INVENTORY },
        dbPath: "proj.db",
      });
      expect(next.inventory).toEqual({ status: "ready", items: SAMPLE_INVENTORY });
      expect(next.project.dbPath).toBe("proj.db");
    });

    it("SET_INVENTORY(error)は件数を 0 にせず失敗理由を保持する", () => {
      const next = appReducer(initialState, {
        type: "SET_INVENTORY",
        result: { status: "error", message: "scan の起動に失敗しました" },
      });
      expect(next.inventory).toEqual({ status: "error", message: "scan の起動に失敗しました" });
    });

    it("SET_FINDINGS・SET_SQL_ADVICE で指摘一覧と SQL指摘を保持する", () => {
      const withFindings = appReducer(initialState, {
        type: "SET_FINDINGS",
        result: { status: "ready", items: SAMPLE_FINDINGS },
      });
      const next = appReducer(withFindings, {
        type: "SET_SQL_ADVICE",
        result: { status: "ready", items: SAMPLE_SQL_FINDINGS },
      });
      expect(next.findings).toEqual({ status: "ready", items: SAMPLE_FINDINGS });
      expect(next.sqlAdvice).toEqual({ status: "ready", items: SAMPLE_SQL_FINDINGS });
    });

    it("SET_FINDINGS(error)は空配列に潰さず失敗理由を保持する", () => {
      const next = appReducer(initialState, {
        type: "SET_FINDINGS",
        result: { status: "error", message: "lint が SARIF を出力しませんでした" },
      });
      expect(next.findings).toEqual({ status: "error", message: "lint が SARIF を出力しませんでした" });
    });

    it("NAV は入力フォルダ・資産一覧・指摘を保つ(タブ移動で結果が消えない)", () => {
      const seed = withState({
        mode: "results",
        project: { inputDir: "C:\\資産", dbPath: "proj.db", copybookPaths: [] },
        inventory: { status: "ready", items: SAMPLE_INVENTORY },
        findings: { status: "ready", items: SAMPLE_FINDINGS },
      });
      const away = appReducer(seed, { type: "NAV", screen: "findings" });
      const back = appReducer(away, { type: "NAV", screen: "explorer" });
      expect(back.mode).toBe("results");
      expect(back.project.inputDir).toBe("C:\\資産");
      expect(back.inventory).toEqual({ status: "ready", items: SAMPLE_INVENTORY });
      expect(back.findings).toEqual({ status: "ready", items: SAMPLE_FINDINGS });
    });
  });

  describe("設定(ルール・しきい値・既定文字コード・コピー句パス入力)", () => {
    it("SET_RULE_SEARCH でルール検索語を保持する", () => {
      const next = appReducer(initialState, { type: "SET_RULE_SEARCH", value: "セキュリティ" });
      expect(next.ruleSearch).toBe("セキュリティ");
    });

    it("TOGGLE_RULE で1件を無効にし、押し直すと有効へ戻す", () => {
      const off = appReducer(initialState, { type: "TOGGLE_RULE", id: "R017" });
      expect(off.rulesDisabled).toEqual({ R017: true });
      const on = appReducer(off, { type: "TOGGLE_RULE", id: "R017" });
      expect(on.rulesDisabled).toEqual({});
    });

    it("SET_RULES_ENABLED で指定した ID を一括で無効・有効にする", () => {
      const off = appReducer(initialState, {
        type: "SET_RULES_ENABLED",
        ids: ["R026", "R027"],
        enabled: false,
      });
      expect(off.rulesDisabled).toEqual({ R026: true, R027: true });
      const on = appReducer(off, { type: "SET_RULES_ENABLED", ids: ["R026"], enabled: true });
      expect(on.rulesDisabled).toEqual({ R027: true });
    });

    it("SET_SEVERITY_THRESHOLD で表示する重大度のしきい値を保持する", () => {
      const next = appReducer(initialState, { type: "SET_SEVERITY_THRESHOLD", severity: "medium" });
      expect(next.severityThreshold).toBe("medium");
    });

    it("SET_DEFAULT_ENCODING で既定の文字コードを保持する", () => {
      const next = appReducer(initialState, { type: "SET_DEFAULT_ENCODING", value: "手動: EBCDIC CP930" });
      expect(next.defaultEncoding).toBe("手動: EBCDIC CP930");
    });

    it("SET_NEW_COPYBOOK_PATH で追加中の入力を保持する", () => {
      const next = appReducer(initialState, { type: "SET_NEW_COPYBOOK_PATH", value: "D:\\copylib2" });
      expect(next.newCopybookPath).toBe("D:\\copylib2");
    });

    it("既定の文字コードは資産エクスプローラーの手動指定と同じ語彙である", () => {
      expect(MANUAL_ENCODING_OPTIONS).toContain(initialState.defaultEncoding);
    });
  });

  describe("diff(修正案の選択・モード・判定)", () => {
    it("選択中の修正案は相対パスで持つ(再解析で並びが変わっても取り違えない)", () => {
      expect(initialState.fixSelected).toBe("");
      const next = appReducer(initialState, { type: "SELECT_FIX", relPath: "cobol/SYK007.cbl" });
      expect(next.fixSelected).toBe("cobol/SYK007.cbl");
    });

    it("SET_DIFF_MODE で確認と書き出しを切り替える", () => {
      const next = appReducer(initialState, { type: "SET_DIFF_MODE", mode: "apply" });
      expect(next.diffMode).toBe("apply");
    });

    it("SET_FIX_DECISION は相対パスごとに判定を記録し、同じ判定で未判定へ戻す", () => {
      const adopted = appReducer(initialState, {
        type: "SET_FIX_DECISION",
        relPath: "cobol/SYK007.cbl",
        decision: "adopted",
      });
      expect(adopted.decisions).toEqual({ "cobol/SYK007.cbl": "adopted" });
      const cleared = appReducer(adopted, {
        type: "SET_FIX_DECISION",
        relPath: "cobol/SYK007.cbl",
        decision: "adopted",
      });
      expect(cleared.decisions).toEqual({});
    });
  });

  describe("レポート出力(形式・出力先)", () => {
    it("SET_REPORT_FORMAT で表示する形式を保持する", () => {
      const next = appReducer(initialState, { type: "SET_REPORT_FORMAT", format: "テキスト" });
      expect(next.reportFormat).toBe("テキスト");
    });

    it("SET_REPORT_PATH で出力先フォルダを保持する", () => {
      const next = appReducer(initialState, { type: "SET_REPORT_PATH", value: "D:\\出力" });
      expect(next.reportPath).toBe("D:\\出力");
    });

    it("出力先の初期値は空文字であり、プロジェクトファイルの置き場所を用いることを表す", () => {
      expect(initialState.reportPath).toBe("");
    });

    it("engine が章を選べないため、章の取捨を状態へ持たない", () => {
      expect("reportIncludeGraph" in initialState).toBe(false);
      expect("reportIncludeFindings" in initialState).toBe(false);
      expect("reportIncludeSql" in initialState).toBe(false);
    });
  });

  describe("分割ペインの幅と畳み込み", () => {
    it("初期の寸法は SPLIT_PANES の initial であり、下限を下回らない", () => {
      for (const pane of Object.keys(SPLIT_PANES) as SplitPaneId[]) {
        const limits = SPLIT_PANES[pane];
        expect(initialState.paneWidths[pane]).toBe(limits.initial);
        expect(limits.initial).toBeGreaterThanOrEqual(limits.min);
      }
    });

    it("可動上限を状態として持たない(コンテナの実寸から導くため)", () => {
      for (const limits of Object.values(SPLIT_PANES)) {
        expect("max" in limits).toBe(false);
        expect(limits.oppositeMin).toBeGreaterThan(0);
      }
    });

    it("初期の寸法は SPLIT_PANES のキーから組む(ペインの増減で直す箇所を増やさない)", () => {
      expect(Object.keys(initialState.paneWidths)).toEqual(Object.keys(SPLIT_PANES));
    });

    it("SET_PANE_WIDTH は指定した画面の幅だけを変える", () => {
      const next = appReducer(initialState, {
        type: "SET_PANE_WIDTH",
        pane: "viewerTranslation",
        width: 300,
      });
      expect(next.paneWidths.viewerTranslation).toBe(300);
      expect(next.paneWidths.explorerDetail).toBe(SPLIT_PANES.explorerDetail.initial);
      expect(next.paneWidths.graphDetail).toBe(SPLIT_PANES.graphDetail.initial);
      expect(next.paneWidths.sqlDetail).toBe(SPLIT_PANES.sqlDetail.initial);
    });

    it("画面を移っても幅を保つ", () => {
      const resized = appReducer(initialState, {
        type: "SET_PANE_WIDTH",
        pane: "explorerDetail",
        width: 420,
      });
      const moved = appReducer(appReducer(resized, { type: "NAV", screen: "graph" }), {
        type: "NAV",
        screen: "explorer",
      });
      expect(moved.paneWidths.explorerDetail).toBe(420);
    });

    it("解析の実行と完了で幅を初期値へ戻さない", () => {
      const resized = appReducer(initialState, {
        type: "SET_PANE_WIDTH",
        pane: "graphDetail",
        width: 300,
      });
      const finished = appReducer(appReducer(resized, { type: "START_RUN" }), { type: "FINISH_RUN" });
      expect(finished.paneWidths.graphDetail).toBe(300);
    });

    it("TOGGLE_GRAPH_DETAIL は呼出関係図の右ペインの畳み込みを切り替える", () => {
      expect(initialState.graphDetailCollapsed).toBe(false);
      const collapsed = appReducer(initialState, { type: "TOGGLE_GRAPH_DETAIL" });
      expect(collapsed.graphDetailCollapsed).toBe(true);
      expect(appReducer(collapsed, { type: "TOGGLE_GRAPH_DETAIL" }).graphDetailCollapsed).toBe(false);
    });

    it("畳んでも幅は保つ(戻したときに元の幅で開く)", () => {
      const resized = appReducer(initialState, {
        type: "SET_PANE_WIDTH",
        pane: "graphDetail",
        width: 320,
      });
      const collapsed = appReducer(resized, { type: "TOGGLE_GRAPH_DETAIL" });
      expect(collapsed.paneWidths.graphDetail).toBe(320);
    });

    it("ソースビューアの対訳ペインは、最小ウィンドウ幅でも原本へ 85 桁分を残せる下限である", () => {
      // 最小ウィンドウ幅 1120px(tokens の --ci-min-width)から、2ペインの外周(左右の余白 24px)と
      // ペインの間(余白 20px + ハンドル 6px)、対訳ペインの下限を引いた残りが原本ペインの幅である。
      const remaining = 1120 - 24 - 26 - SPLIT_PANES.viewerTranslation.min;
      // 固定形式 80 桁 + 行番号 5 桁を 12px 等幅(送り 7.2px)で描くのに要する幅。
      expect(remaining).toBeGreaterThanOrEqual(85 * 7.2);
    });

    it("COMMIT_PANE_SIZE は操作の完了を数える(保存の合図)", () => {
      expect(initialState.paneCommitCount).toBe(0);
      const once = appReducer(initialState, { type: "COMMIT_PANE_SIZE" });
      expect(once.paneCommitCount).toBe(1);
      expect(appReducer(once, { type: "COMMIT_PANE_SIZE" }).paneCommitCount).toBe(2);
    });

    it("寸法を変えただけでは保存の合図にならない(ドラッグ中に書き込まない)", () => {
      const resized = appReducer(initialState, {
        type: "SET_PANE_WIDTH",
        pane: "diffList",
        width: 420,
      });
      expect(resized.paneCommitCount).toBe(initialState.paneCommitCount);
    });
  });

  describe("restorePaneSizes(保存した寸法の復元)", () => {
    it("知っているペインの寸法だけを戻す", () => {
      const restored = restorePaneSizes(
        { explorerDetail: 600, sqlList: 300 },
        initialState.paneWidths,
      );
      expect(restored.explorerDetail).toBe(600);
      expect(restored.sqlList).toBe(300);
      expect(restored.diffList).toBe(SPLIT_PANES.diffList.initial);
    });

    it("知らないキーは捨てる", () => {
      const restored = restorePaneSizes({ 廃止したペイン: 999 }, initialState.paneWidths);
      expect(restored).toEqual(initialState.paneWidths);
      expect("廃止したペイン" in restored).toBe(false);
    });

    it("下限を割る寸法は下限で丸める", () => {
      const restored = restorePaneSizes({ viewerTranslation: 10 }, initialState.paneWidths);
      expect(restored.viewerTranslation).toBe(SPLIT_PANES.viewerTranslation.min);
    });

    it("画素の端数を丸める", () => {
      expect(restorePaneSizes({ diffList: 320.6 }, initialState.paneWidths).diffList).toBe(321);
    });
  });

  describe("TOGGLE_CODE_FOCUS(コードの最大化)", () => {
    it("切り替えるたびに反転する", () => {
      expect(initialState.codeFocus).toBe(false);
      const focused = appReducer(initialState, { type: "TOGGLE_CODE_FOCUS" });
      expect(focused.codeFocus).toBe(true);
      expect(appReducer(focused, { type: "TOGGLE_CODE_FOCUS" }).codeFocus).toBe(false);
    });

    it("畳んでも寸法は保つ(戻したときに畳む前の寸法で開く)", () => {
      const resized = appReducer(initialState, {
        type: "SET_PANE_WIDTH",
        pane: "findingsList",
        width: 360,
      });
      const focused = appReducer(resized, { type: "TOGGLE_CODE_FOCUS" });
      expect(focused.paneWidths.findingsList).toBe(360);
    });
  });

  it("ルールの有効数を固定値として持たない(無効化集合から導く)", () => {
    expect("rulesEnabled" in initialState).toBe(false);
  });

  describe("RESTORE_SETTINGS(保存した設定の復元)", () => {
    const saved = {
      disabledRules: ["R004", "U001"],
      severityThreshold: "medium",
      defaultEncoding: MANUAL_ENCODING_OPTIONS[1],
      copybookPaths: ["C:\\copy", "C:\\copy2"],
      paneSizes: {},
    };

    it("保存した4項目を戻し、復元済みの印を立てる", () => {
      const next = appReducer(initialState, { type: "RESTORE_SETTINGS", settings: saved });
      expect(next.settingsLoaded).toBe(true);
      expect(next.rulesDisabled).toEqual({ R004: true, U001: true });
      expect(next.severityThreshold).toBe("medium");
      expect(next.defaultEncoding).toBe(MANUAL_ENCODING_OPTIONS[1]);
      expect(next.project.copybookPaths).toEqual(["C:\\copy", "C:\\copy2"]);
    });

    it("設定が空でも復元済みの印を立てる(初期値のまま保存を始められる)", () => {
      const next = appReducer(initialState, {
        type: "RESTORE_SETTINGS",
        settings: {
          disabledRules: [],
          severityThreshold: "",
          defaultEncoding: "",
          copybookPaths: [],
          paneSizes: {},
        },
      });
      expect(next.settingsLoaded).toBe(true);
      expect(next.rulesDisabled).toEqual({});
      expect(next.severityThreshold).toBe(initialState.severityThreshold);
      expect(next.defaultEncoding).toBe(initialState.defaultEncoding);
    });

    it("選択肢に無い重大度・文字コードは捨てて現在の値を残す", () => {
      const next = appReducer(initialState, {
        type: "RESTORE_SETTINGS",
        settings: {
          disabledRules: [],
          severityThreshold: "critical",
          defaultEncoding: "手動: 存在しない文字コード",
          copybookPaths: [],
          paneSizes: {},
        },
      });
      expect(next.severityThreshold).toBe(initialState.severityThreshold);
      expect(next.defaultEncoding).toBe(initialState.defaultEncoding);
    });

    it("保存した分割ペインの寸法を戻す", () => {
      const next = appReducer(initialState, {
        type: "RESTORE_SETTINGS",
        settings: { ...saved, paneSizes: { explorerDetail: 640, findingsList: 300 } },
      });
      expect(next.paneWidths.explorerDetail).toBe(640);
      expect(next.paneWidths.findingsList).toBe(300);
      // 保存に無いペインは初期の寸法のままである。
      expect(next.paneWidths.viewerTranslation).toBe(SPLIT_PANES.viewerTranslation.initial);
    });

    it("復元は資産フォルダの選択を変えない", () => {
      const base = withState({
        project: { inputDir: "C:\\資産", dbPath: "C:\\db", copybookPaths: [] },
      });
      const next = appReducer(base, { type: "RESTORE_SETTINGS", settings: saved });
      expect(next.project.inputDir).toBe("C:\\資産");
      expect(next.project.dbPath).toBe("C:\\db");
    });
  });

  it("状態を破壊的に変更せず新しいオブジェクトを返す", () => {
    const next = appReducer(initialState, { type: "NAV", screen: "graph" });
    expect(next).not.toBe(initialState);
    expect(initialState.screen).toBe("explorer");
  });
});

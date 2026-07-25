import { describe, it, expect } from "vitest";
import { appReducer } from "./appReducer";
import { initialState, type AppState } from "./appState";
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

    it("START_RUN は前回の資産一覧・指摘・SQL助言を破棄する(古い結果を残さない)", () => {
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

  describe("JUMP(画面横断ジャンプ、design:1263)", () => {
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

  describe("SQL助言のフィルタと選択(指摘一覧と同じく AppState に持つ)", () => {
    it("TOGGLE_SQL_SEVERITY で重大度を切り替え、他の重大度は保つ", () => {
      const next = appReducer(initialState, { type: "TOGGLE_SQL_SEVERITY", severity: "medium" });
      expect(next.sqlSeverity).toEqual({ high: true, medium: false, low: true, warning: true });
      expect(next.findingSeverity).toEqual(initialState.findingSeverity);
    });

    it("SET_SQL_RULE・SET_SQL_FILE・SET_SQL_TEXT・SET_SQL_SORT を保持する", () => {
      let next = appReducer(initialState, { type: "SET_SQL_RULE", value: "S002" });
      next = appReducer(next, { type: "SET_SQL_FILE", value: "cobol/SYK007.cbl" });
      next = appReducer(next, { type: "SET_SQL_TEXT", value: "SARGable" });
      next = appReducer(next, { type: "SET_SQL_SORT", sort: "line" });
      expect(next.sqlRule).toBe("S002");
      expect(next.sqlFile).toBe("cobol/SYK007.cbl");
      expect(next.sqlText).toBe("SARGable");
      expect(next.sqlSort).toBe("line");
      // 指摘一覧のフィルタは独立に保たれる。
      expect(next.findingRule).toBe("all");
      expect(next.findingSort).toBe("sev");
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

    it("SET_FINDINGS・SET_SQL_ADVICE で指摘と SQL 助言を保持する", () => {
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

  it("ルールの有効数を固定値として持たない(無効化集合から導く)", () => {
    expect("rulesEnabled" in initialState).toBe(false);
  });

  it("状態を破壊的に変更せず新しいオブジェクトを返す", () => {
    const next = appReducer(initialState, { type: "NAV", screen: "graph" });
    expect(next).not.toBe(initialState);
    expect(initialState.screen).toBe("explorer");
  });
});

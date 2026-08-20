import { render, screen, fireEvent, within } from "@testing-library/react";
import { describe, it, expect, beforeEach, vi } from "vitest";
import type { CobolInsightApi, SourceTextResult } from "../../../shared/engine-api";
import { Shell } from "./Shell";
import { App } from "../App";
import {
  ProjectProvider,
  initialProjectState,
  type ProjectState,
} from "../state/projectStore";
import { SettingsProvider } from "../state/settingsStore";
import { WorkbenchProvider } from "../state/workbenchStore";
import { FIXTURE_CATALOG } from "../data/__fixtures__/catalog";
import { SAMPLE_FINDINGS, SAMPLE_INVENTORY } from "../data/__fixtures__/samples";
import type { FakeEditor } from "../screens/viewer/monacoFake";

/**
 * Monaco は Worker と実 DOM の計測を要するため jsdom では動かない。描画ライブラリの入口を
 * 差し替え、本文の面が実際に組み上がるところまでを試験の対象にする。
 */
const monacoStore = vi.hoisted(() => ({ editors: [] as FakeEditor[] }));

vi.mock("../vendor/monacoEditor", async () => {
  const { createMonacoFake } = await import("../screens/viewer/monacoFake");
  return { monacoEditor: () => createMonacoFake(monacoStore) };
});

vi.mock("../vendor/monacoLanguages", () => ({
  GENERATED_LANGUAGE_ID: { python: "python", java: "java" },
  registerGeneratedLanguages: () => undefined,
}));

/** 走査と検出を終えた状態。 */
const ANALYZED: ProjectState = {
  ...initialProjectState,
  mode: "results",
  inputDir: "C:\\資産",
  dbPath: "C:\\out\\cobol-insight.db",
  inventory: { status: "ready", items: [...SAMPLE_INVENTORY] },
  findings: { status: "ready", items: [...SAMPLE_FINDINGS] },
  sqlAdvice: { status: "ready", items: [] },
  catalog: FIXTURE_CATALOG,
};

const SOURCE: SourceTextResult = {
  text: "       IDENTIFICATION DIVISION.\n       PROGRAM-ID. SYK001.\n",
  codepage: "Shift_JIS",
  truncated: false,
  unsupported: false,
};

/**
 * main 側の口を最小限だけ差し替える。この試験はシェルの組み立てを見るものであり、
 * engine の起動と成果物の読取は別の試験が受け持つ。
 */
beforeEach(() => {
  const stub: Partial<CobolInsightApi> = {
    getOutputPaths: vi.fn().mockRejectedValue(new Error("試験では成果物の位置を持たない")),
    readSettings: vi.fn().mockRejectedValue(new Error("試験では設定を持たない")),
    readSourceText: vi.fn().mockResolvedValue(SOURCE),
  };
  window.cobolInsight = stub as CobolInsightApi;
});

function renderShell(project: ProjectState = initialProjectState): void {
  render(
    <SettingsProvider>
      <ProjectProvider initial={project}>
        <WorkbenchProvider>
          <Shell />
        </WorkbenchProvider>
      </ProjectProvider>
    </SettingsProvider>,
  );
}

describe("シェルの骨組み", () => {
  it("解析エンジンへ繋がらなくても描ける", () => {
    render(<App />);
    expect(screen.getByRole("banner")).toHaveTextContent("COBOL Insight");
  });

  it("製品名が文書で唯一の h1 である", () => {
    renderShell();
    const level1 = screen.getAllByRole("heading", { level: 1 });
    expect(level1).toHaveLength(1);
    expect(level1[0]).toHaveTextContent("COBOL Insight");
  });

  it("アクティビティバーに 5 つの入口を並べる", () => {
    renderShell();
    const bar = screen.getByRole("navigation", { name: "機能の切り替え" });
    expect(within(bar).getAllByRole("button")).toHaveLength(5);
    expect(within(bar).getByRole("button", { name: "エクスプローラー" })).toBeInTheDocument();
  });

  it("資産フォルダを選ぶ前は、側パネルが選択を促す", () => {
    renderShell();
    expect(screen.getByRole("region", { name: "資産フォルダを選んでください" })).toBeInTheDocument();
  });

  it("ステータスバーへプロジェクトの場所と件数を出す", () => {
    renderShell(ANALYZED);
    const status = screen.getByRole("contentinfo");
    expect(status).toHaveTextContent("C:\\資産");
    expect(status).toHaveTextContent("解析完了");
    expect(status).toHaveTextContent(`資産 ${SAMPLE_INVENTORY.length}`);
  });
});

describe("ツリーから本文を開く", () => {
  it("資産を押すとタブが開き、その資産の面を出す", () => {
    renderShell(ANALYZED);
    fireEvent.click(screen.getByTestId("tree-cobol/SYK001.cbl"));
    expect(screen.getByRole("tab", { name: "cobol/SYK001.cbl" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByTestId("tabpanel-source:cobol/SYK001.cbl")).toBeInTheDocument();
  });

  it("タブを閉じると空状態へ戻る", () => {
    renderShell(ANALYZED);
    fireEvent.click(screen.getByTestId("tree-cobol/SYK001.cbl"));
    fireEvent.click(screen.getByRole("button", { name: "SYK001.cbl を閉じる" }));
    expect(screen.queryByRole("tab", { name: "cobol/SYK001.cbl" })).toBeNull();
    expect(screen.getByRole("region", { name: "資産を開いていません" })).toBeInTheDocument();
  });

  it("フォルダを押すと畳み、配下の資産を並べない", () => {
    renderShell(ANALYZED);
    fireEvent.click(screen.getByTestId("tree-cobol"));
    expect(screen.queryByTestId("tree-cobol/SYK001.cbl")).toBeNull();
  });
});

describe("アクティビティバーからタブを開く", () => {
  it("呼出関係図を押すとタブが開く", () => {
    renderShell(ANALYZED);
    fireEvent.click(screen.getByTestId("activity-graph"));
    expect(screen.getByRole("tab", { name: "呼出関係図" })).toHaveAttribute("aria-selected", "true");
  });

  it("エクスプローラーを押し直すと側パネルを畳む", () => {
    renderShell(ANALYZED);
    fireEvent.click(screen.getByTestId("activity-explorer"));
    expect(screen.queryByRole("tree", { name: "資産" })).toBeNull();
    fireEvent.click(screen.getByTestId("activity-explorer"));
    expect(screen.getByRole("tree", { name: "資産" })).toBeInTheDocument();
  });
});

describe("下部パネルの指摘", () => {
  it("lint の指摘を表へ並べる", () => {
    renderShell(ANALYZED);
    const table = screen.getByRole("table", { name: "検出した指摘の一覧" });
    expect(within(table).getAllByRole("row")).toHaveLength(SAMPLE_FINDINGS.length + 1);
  });

  it("行を押すとその資産の該当行を開く", () => {
    renderShell(ANALYZED);
    const table = screen.getByRole("table", { name: "検出した指摘の一覧" });
    fireEvent.click(within(table).getAllByRole("row")[1]);
    expect(screen.getAllByRole("tab").some((tab) => tab.getAttribute("aria-selected") === "true")).toBe(
      true,
    );
  });

  it("実行ログへ切り替えられる", () => {
    renderShell(ANALYZED);
    fireEvent.click(screen.getByTestId("bottom-log"));
    expect(screen.getByTestId("bottom-log")).toHaveAttribute("aria-selected", "true");
    expect(screen.queryByRole("table", { name: "検出した指摘の一覧" })).toBeNull();
  });

  it("畳んで開ける", () => {
    renderShell(ANALYZED);
    fireEvent.click(screen.getByRole("button", { name: "下部パネルを畳む" }));
    expect(screen.queryByTestId("bottom-log")).toBeNull();
  });
});

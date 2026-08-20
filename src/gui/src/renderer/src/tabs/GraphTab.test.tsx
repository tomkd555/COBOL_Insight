import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { describe, it, expect, beforeEach, vi } from "vitest";
import type { CobolInsightApi, GraphData } from "../../../shared/engine-api";
import { GraphTab } from "./GraphTab";
import { ProjectProvider, initialProjectState, type ProjectState } from "../state/projectStore";
import { SettingsProvider } from "../state/settingsStore";
import { WorkbenchProvider } from "../state/workbenchStore";

/**
 * Cytoscape は canvas へ描くため jsdom では動かない。描画ライブラリの入口を差し替え、
 * タブの組み立てと実行順の一覧までを試験の対象にする。
 */
vi.mock("../vendor/graphLibrary", () => ({
  graphLibrary: () => () => ({
    on: () => undefined,
    batch: (run: () => void) => run(),
    elements: () => ({ remove: () => undefined }),
    add: () => undefined,
    layout: () => ({ run: () => undefined }),
    nodes: () => ({ removeClass: () => undefined }),
    getElementById: () => ({ addClass: () => undefined }),
    destroy: () => undefined,
  }),
}));

const GRAPH_ID_BASE = 1_000_000_000_000;

const SAMPLE: GraphData = {
  nodes: [
    { id: 1, type: "JCL", label: "SYKD010.jcl" },
    { id: 2, type: "PROGRAM", label: "SYK001" },
    { id: GRAPH_ID_BASE + 1, type: "JOB", label: "SYKD010" },
    { id: GRAPH_ID_BASE + 2, type: "STEP", label: "STEP010" },
  ],
  edges: [
    {
      from: GRAPH_ID_BASE + 1,
      to: GRAPH_ID_BASE + 2,
      kind: "EXECUTION",
      resolution: "CONSTANT",
      seq: 1,
      line: 20,
    },
    { from: GRAPH_ID_BASE + 2, to: 2, kind: "EXECUTION", resolution: "CONSTANT", seq: 1, line: 20 },
  ],
  paragraphs: [{ id: 11, programSourceId: 2, name: "主処理", startLine: 40, endLine: 90 }],
  paragraphEdges: [],
};

const readGraph = vi.fn();
const runCallgraph = vi.fn();

beforeEach(() => {
  vi.clearAllMocks();
  readGraph.mockResolvedValue(SAMPLE);
  runCallgraph.mockResolvedValue({
    subcommand: "call-graph",
    exitCode: 0,
    summary: null,
    stdout: "",
    stderr: "",
    outputs: { svg: "C:\\out\\callgraph.svg" },
  });
  const stub: Partial<CobolInsightApi> = { readGraph, runCallgraph };
  window.cobolInsight = stub as CobolInsightApi;
});

const ANALYZED: ProjectState = {
  ...initialProjectState,
  mode: "results",
  inputDir: "C:\\資産",
  dbPath: "C:\\out\\cobol-insight.db",
  inventory: {
    status: "ready",
    items: [
      {
        id: 1,
        path: "jcl/SYKD010.jcl",
        name: "SYKD010.jcl",
        type: "JCL",
        codepage: "Shift_JIS",
        byteSize: 300,
        findingCount: 0,
      },
      {
        id: 2,
        path: "cobol/SYK001.cbl",
        name: "SYK001.cbl",
        type: "PROGRAM",
        codepage: "Shift_JIS",
        byteSize: 900,
        findingCount: 0,
      },
    ],
  },
};

function renderTab(project: ProjectState = ANALYZED): void {
  render(
    <SettingsProvider>
      <ProjectProvider initial={project}>
        <WorkbenchProvider>
          <GraphTab />
        </WorkbenchProvider>
      </ProjectProvider>
    </SettingsProvider>,
  );
}

describe("呼出関係の読み出し", () => {
  it("プロジェクトファイルから読み、call-graph は起動しない", async () => {
    renderTab();
    await waitFor(() => expect(readGraph).toHaveBeenCalledWith("C:\\out\\cobol-insight.db"));
    expect(runCallgraph).not.toHaveBeenCalled();
  });

  it("読み出せないときは理由を示し、もう一度読み出せる", async () => {
    readGraph.mockRejectedValue(new Error("プロジェクトファイルを開けませんでした"));
    renderTab();
    await waitFor(() =>
      expect(
        screen.getByRole("region", { name: "呼出関係を読み出せませんでした" }),
      ).toBeInTheDocument(),
    );
  });

  it("解析していなければ図を出さず、次に取る操作を示す", () => {
    renderTab({ ...initialProjectState, mode: "empty" });
    expect(screen.getByRole("region", { name: "解析結果がありません" })).toBeInTheDocument();
  });
});

describe("実行順の一覧", () => {
  it("ジョブを起点として並べる", async () => {
    renderTab();
    const tree = await screen.findByRole("tree", { name: /実行順/ });
    expect(tree).toBeInTheDocument();
    expect(screen.getByText("SYKD010")).toBeInTheDocument();
  });

  it("開くとステップ、さらにプログラムと段落まで辿れる", async () => {
    renderTab();
    const job = await screen.findByTestId(`trace-job:${GRAPH_ID_BASE + 1}`);
    fireEvent.keyDown(job, { key: "ArrowRight" });
    const step = await screen.findByText("STEP010");
    fireEvent.keyDown(step.closest('[role="treeitem"]') as HTMLElement, { key: "ArrowRight" });
    const program = await screen.findByText("SYK001");
    fireEvent.keyDown(program.closest('[role="treeitem"]') as HTMLElement, { key: "ArrowRight" });
    expect(await screen.findByText("主処理")).toBeInTheDocument();
    expect(screen.getByText(":40")).toBeInTheDocument();
  });

  it("節を押すと図の選択も同じノードへ移る", async () => {
    renderTab();
    const job = await screen.findByTestId(`trace-job:${GRAPH_ID_BASE + 1}`);
    fireEvent.click(job);
    expect(job).toHaveAttribute("aria-selected", "true");
    // ノード情報は既定で畳んである。図が主役のため、選んだ内容は開いてから確かめる。
    fireEvent.click(screen.getByRole("button", { name: "ノード情報と凡例を開く" }));
    await waitFor(() => expect(screen.getByText("SYKD010", { selector: ".ci-graph-detail__name" })).toBeInTheDocument());
  });
});

describe("図の書き出し", () => {
  it("SVG 出力だけが call-graph を起動する", async () => {
    renderTab();
    await screen.findByRole("tree", { name: /実行順/ });
    fireEvent.click(screen.getByRole("button", { name: "SVG 出力" }));
    await waitFor(() => expect(runCallgraph).toHaveBeenCalledTimes(1));
    expect(runCallgraph.mock.calls[0][0]).toMatchObject({
      inputDir: "C:\\資産",
      db: "C:\\out\\cobol-insight.db",
      svgFile: "C:\\out\\callgraph.svg",
    });
    expect(await screen.findByText(/SVG で書き出しました/)).toBeInTheDocument();
  });
});

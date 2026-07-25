import { render, screen, fireEvent, waitFor, act } from "@testing-library/react";
import type { ReactElement } from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { GraphScreen } from "./GraphScreen";
import { AppStateProvider, useAppState, useAppDispatch } from "../../state/AppStateContext";
import { initialState, type AppState } from "../../state/appState";
import { SAMPLE_GRAPH } from "./fixtures";
import { SAMPLE_INVENTORY } from "../explorer/fixtures";
import type { CobolInsightApi, EngineResult } from "../../../../shared/engine-api";

/**
 * Cytoscape の canvas 描画は jsdom で成立しないため、描画ライブラリの入口(vendor/graphLibrary)を
 * 差し替える。差し替え先は要素の追加・レイアウト実行・tap ハンドラの登録を記録するだけの偽物で、
 * これにより「どの要素が図へ渡ったか」と「ノード選択が画面へ伝わるか」を検証できる。
 */
const cy = vi.hoisted(() => ({
  added: [] as { group: string; data: { id: string } }[],
  tapHandlers: [] as ((event: { target: { id: () => string } }) => void)[],
  layoutRuns: 0,
  selectedClasses: [] as string[],
}));

vi.mock("../../vendor/graphLibrary", () => ({
  graphLibrary: () => () => ({
    on: (
      _event: string,
      _selector: string,
      handler: (event: { target: { id: () => string } }) => void,
    ) => {
      cy.tapHandlers.push(handler);
    },
    batch: (fn: () => void) => {
      fn();
    },
    elements: () => ({ remove: () => undefined }),
    add: (elements: { group: string; data: { id: string } }[]) => {
      cy.added = elements;
    },
    layout: () => ({
      run: () => {
        cy.layoutRuns += 1;
      },
    }),
    nodes: () => ({ removeClass: () => undefined }),
    getElementById: (id: string) => ({
      addClass: (className: string) => {
        cy.selectedClasses.push(`${id}:${className}`);
      },
    }),
    destroy: () => undefined,
  }),
}));

const INPUT_DIR = "C:\\資産\\SYK";

function callgraphResult(overrides: Partial<EngineResult> = {}): EngineResult {
  return {
    subcommand: "callgraph",
    exitCode: 0,
    summary: null,
    stdout: "",
    stderr: "",
    outputs: { json: "callgraph.json" },
    ...overrides,
  };
}

let runCallgraph: ReturnType<typeof vi.fn>;
let readCallgraphJson: ReturnType<typeof vi.fn>;

beforeEach(() => {
  cy.added = [];
  cy.tapHandlers = [];
  cy.layoutRuns = 0;
  cy.selectedClasses = [];
  runCallgraph = vi.fn().mockResolvedValue(callgraphResult());
  readCallgraphJson = vi.fn().mockResolvedValue(SAMPLE_GRAPH);
  window.cobolInsight = { runCallgraph, readCallgraphJson } as unknown as CobolInsightApi;
});

afterEach(() => {
  delete (window as { cobolInsight?: CobolInsightApi }).cobolInsight;
});

/** 解析実行が済んだ状態(mode=results・入力フォルダあり)。 */
function analyzedState(overrides: Partial<AppState> = {}): AppState {
  return {
    ...initialState,
    mode: "results",
    screen: "graph",
    project: { inputDir: INPUT_DIR, dbPath: "C:\\proj\\cobol-insight.db", copybookPaths: ["C:\\資産\\copy"] },
    ...overrides,
  };
}

/** トースト文言と現在の画面を観測できる器。 */
function Harness(): ReactElement {
  const state = useAppState();
  return (
    <>
      <p data-testid="toast">{state.toastMsg ?? ""}</p>
      <p data-testid="screen">{state.screen}</p>
      <GraphScreen />
    </>
  );
}

function renderGraph(seed?: AppState): void {
  render(
    <AppStateProvider initialState={seed}>
      <Harness />
    </AppStateProvider>,
  );
}

/** グラフ取得の完了(ツールバーの件数表示)を待つ。 */
async function waitForGraph(): Promise<void> {
  await screen.findByText(/表示 \d+ \/ 全 18 ノード/);
}

/** 図へ渡ったノード要素の ID 一覧。 */
function renderedNodeIds(): string[] {
  return cy.added.filter((element) => element.group === "nodes").map((element) => element.data.id);
}

/** ノードの tap(Cytoscape の選択)を模す。canvas 由来の選択も React の更新として扱う。 */
function tapNode(id: string): void {
  act(() => {
    for (const handler of cy.tapHandlers) {
      handler({ target: { id: () => id } });
    }
  });
}

describe("GraphScreen(呼出関係図)の4状態", () => {
  it("解析未実行では誘導を出し、callgraph を起動しない", () => {
    renderGraph();
    expect(screen.getByRole("region", { name: "解析結果がありません" })).toBeInTheDocument();
    expect(runCallgraph).not.toHaveBeenCalled();
  });

  it("解析実行中は呼出関係の構築中として示す", () => {
    renderGraph({ ...initialState, mode: "running", screen: "graph" });
    expect(screen.getByRole("status")).toHaveTextContent("呼出関係を構築しています…");
    expect(runCallgraph).not.toHaveBeenCalled();
  });

  it("解析済みなら callgraph を起動し、JSON から図を組む", async () => {
    renderGraph(analyzedState());
    await waitForGraph();
    expect(runCallgraph).toHaveBeenCalledWith({
      inputDir: INPUT_DIR,
      copybookPaths: ["C:\\資産\\copy"],
      db: "C:\\proj\\cobol-insight.db",
      jsonFile: "C:\\proj\\callgraph.json",
    });
    expect(readCallgraphJson).toHaveBeenCalledWith("callgraph.json");
    expect(screen.getByRole("application", { name: /呼出関係図/ })).toBeInTheDocument();
  });

  it("callgraph の失敗は 0 件と区別し、理由と再試行を示す", async () => {
    runCallgraph.mockRejectedValueOnce(new Error("java が見つからない"));
    renderGraph(analyzedState());
    const region = await screen.findByRole("region", { name: "呼出関係図を取得できませんでした" });
    expect(region).toHaveTextContent("java が見つからない");
    runCallgraph.mockResolvedValue(callgraphResult());
    fireEvent.click(screen.getByRole("button", { name: "再試行" }));
    await waitForGraph();
    expect(runCallgraph).toHaveBeenCalledTimes(2);
  });

  it("JSON の出力先が返らない場合も失敗として扱う", async () => {
    runCallgraph.mockResolvedValueOnce(callgraphResult({ outputs: {} }));
    renderGraph(analyzedState());
    const region = await screen.findByRole("region", { name: "呼出関係図を取得できませんでした" });
    expect(region).toHaveTextContent("JSON の出力先");
    expect(readCallgraphJson).not.toHaveBeenCalled();
  });

  it("非ゼロ終了でも図は隠さず、終了コードを添えた警告を出す", async () => {
    runCallgraph.mockResolvedValueOnce(callgraphResult({ exitCode: 2 }));
    renderGraph(analyzedState());
    await waitForGraph();
    expect(screen.getByRole("alert")).toHaveTextContent("callgraph 終了コード 2");
    expect(renderedNodeIds().length).toBeGreaterThan(0);
  });
});

describe("GraphScreen の部分展開とフィルタ", () => {
  it("初期表示はジョブとトランザクションの起点だけにする", async () => {
    renderGraph(analyzedState());
    await waitForGraph();
    expect(renderedNodeIds()).toEqual(["job:SYKD010", "job:SYKD020", "transaction:SYK8"]);
    expect(screen.getByText("表示 3 / 全 18 ノード")).toBeInTheDocument();
  });

  it("選択したノードの隣接を展開し、畳むと元へ戻す", async () => {
    renderGraph(analyzedState());
    await waitForGraph();
    tapNode("job:SYKD010");
    fireEvent.click(await screen.findByRole("button", { name: /隣接を展開/ }));
    await waitFor(() => expect(renderedNodeIds()).toContain("step:SYKD010.STEP010"));
    expect(screen.getByText("表示 5 / 全 18 ノード")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "隣接を畳む" }));
    await waitFor(() => expect(renderedNodeIds()).not.toContain("step:SYKD010.STEP010"));
  });

  it("ノード種別フィルタを切ると、その種別を図から外す", async () => {
    renderGraph(analyzedState());
    await waitForGraph();
    const chip = screen.getByRole("button", { name: /ジョブ/ });
    expect(chip).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(chip);
    await waitFor(() => expect(renderedNodeIds()).toEqual(["transaction:SYK8"]));
    expect(chip).toHaveAttribute("aria-pressed", "false");
  });

  it("すべての起点種別を切ると、フィルタを戻す案内を出す", async () => {
    renderGraph(analyzedState());
    await waitForGraph();
    fireEvent.click(screen.getByRole("button", { name: /ジョブ/ }));
    fireEvent.click(screen.getByRole("button", { name: /トランザクション/ }));
    expect(await screen.findByText(/ノード種別フィルタで切った種別を戻す/)).toBeInTheDocument();
  });

  it("種別チップは種別ごとのノード件数を示す", async () => {
    renderGraph(analyzedState());
    await waitForGraph();
    expect(screen.getByRole("button", { name: "プログラム 6" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "段落・節 0" })).toBeInTheDocument();
  });
});

describe("GraphScreen のノード一覧(キーボード操作)", () => {
  it("図と同じノードを一覧に並べ、単一のタブ位置だけを持つ", async () => {
    renderGraph(analyzedState());
    await waitForGraph();
    expect(screen.getByRole("listbox", { name: /ノード一覧/ })).toBeInTheDocument();
    const options = screen.getAllByRole("option");
    expect(options).toHaveLength(renderedNodeIds().length);
    expect(options[0]).toHaveTextContent("SYKD010");
    expect(options.filter((option) => option.getAttribute("tabindex") === "0")).toHaveLength(1);
  });

  it("一覧のノードを選ぶと詳細ペインへ反映する", async () => {
    renderGraph(analyzedState());
    await waitForGraph();
    fireEvent.click(screen.getAllByRole("option")[0]);
    const detail = await screen.findByRole("complementary", { name: "ノード情報と凡例" });
    expect(detail).toHaveTextContent("job:SYKD010");
    expect(screen.getAllByRole("option")[0]).toHaveAttribute("aria-selected", "true");
  });

  it("矢印キーで選択を移し、Home・End で端へ移す", async () => {
    renderGraph(analyzedState());
    await waitForGraph();
    const first = screen.getAllByRole("option")[0];
    first.focus();
    fireEvent.keyDown(first, { key: "ArrowDown" });
    await waitFor(() =>
      expect(screen.getAllByRole("option")[1]).toHaveAttribute("aria-selected", "true"),
    );
    fireEvent.keyDown(screen.getAllByRole("option")[1], { key: "End" });
    await waitFor(() =>
      expect(screen.getAllByRole("option")[2]).toHaveAttribute("aria-selected", "true"),
    );
    fireEvent.keyDown(screen.getAllByRole("option")[2], { key: "Home" });
    await waitFor(() =>
      expect(screen.getAllByRole("option")[0]).toHaveAttribute("aria-selected", "true"),
    );
  });

  it("一覧からの選択で隣接の展開とソースを開く操作へ到達できる", async () => {
    renderGraph(analyzedState({ inventory: { status: "ready", items: SAMPLE_INVENTORY } }));
    await waitForGraph();
    fireEvent.click(screen.getAllByRole("option")[0]);
    fireEvent.click(await screen.findByRole("button", { name: /隣接を展開/ }));
    await waitFor(() => expect(renderedNodeIds()).toContain("step:SYKD010.STEP010"));
    fireEvent.click(screen.getByRole("button", { name: /ソースを開く/ }));
    expect(screen.getByTestId("screen")).toHaveTextContent("viewer");
  });
});

describe("GraphScreen の詳細ペイン", () => {
  it("未選択では選択を促す", async () => {
    renderGraph(analyzedState());
    await waitForGraph();
    expect(screen.getByText(/ノードを選択すると詳細を表示する/)).toBeInTheDocument();
  });

  it("選択したノードの種別・ID・属性・入出力エッジを示す", async () => {
    renderGraph(
      analyzedState({
        graphExpanded: { "job:SYKD010": true, "step:SYKD010.STEP020": true, "program:SYK002": true },
      }),
    );
    await waitForGraph();
    tapNode("program:SYK002");
    const detail = await screen.findByRole("complementary", { name: "ノード情報と凡例" });
    expect(detail).toHaveTextContent("プログラム");
    expect(detail).toHaveTextContent("program:SYK002");
    expect(detail).toHaveTextContent("入ってくるエッジ（1）");
    expect(detail).toHaveTextContent("出ていくエッジ（2）");
    // 動的 CALL(データフロー由来)と未解決は破線として明示する。
    expect(detail).toHaveTextContent("破線: データフロー由来");
    expect(detail).toHaveTextContent("破線: 未解決");
  });

  it("未解決ノードは属性(参照変数名)を示す", async () => {
    renderGraph(
      analyzedState({
        graphExpanded: { "job:SYKD010": true, "step:SYKD010.STEP020": true, "program:SYK002": true },
      }),
    );
    await waitForGraph();
    tapNode("unresolved:WS-PROG-NAME");
    const detail = await screen.findByRole("complementary", { name: "ノード情報と凡例" });
    expect(detail).toHaveTextContent("未解決");
    expect(detail).toHaveTextContent("variable");
    expect(detail).toHaveTextContent("WS-PROG-NAME");
  });

  it("資産一覧に対応があるノードはソースへ飛べる", async () => {
    renderGraph(
      analyzedState({
        inventory: { status: "ready", items: SAMPLE_INVENTORY },
        graphExpanded: { "job:SYKD010": true, "step:SYKD010.STEP010": true },
      }),
    );
    await waitForGraph();
    tapNode("program:SYK001");
    fireEvent.click(await screen.findByRole("button", { name: "ソースを開く →" }));
    expect(screen.getByTestId("screen")).toHaveTextContent("viewer");
  });

  it("同名の資産が複数あるノードは、相対パスを添えて開く先を選ばせる", async () => {
    const duplicated = [
      ...SAMPLE_INVENTORY,
      {
        id: 7,
        path: "cobol-old/SYK001.cbl",
        name: "SYK001.cbl",
        type: "PROGRAM",
        codepage: "windows-31j",
        byteSize: 4100,
        findingCount: 0,
      },
    ];
    renderGraph(
      analyzedState({
        inventory: { status: "ready", items: duplicated },
        graphExpanded: { "job:SYKD010": true, "step:SYKD010.STEP010": true },
      }),
    );
    await waitForGraph();
    tapNode("program:SYK001");
    expect(
      await screen.findByRole("button", { name: /cobol\/SYK001\.cbl/ }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /cobol-old\/SYK001\.cbl/ }));
    expect(screen.getByTestId("screen")).toHaveTextContent("viewer");
  });

  it("対応するソースが無いノードにはソースを開く操作を出さない", async () => {
    renderGraph(
      analyzedState({
        inventory: { status: "ready", items: SAMPLE_INVENTORY },
        graphExpanded: { "job:SYKD010": true, "step:SYKD010.STEP010": true },
      }),
    );
    await waitForGraph();
    tapNode("dataset:SYKT.D250718.ORDER.DAILY");
    // 同じ表示名はノード一覧にも出るため、詳細ペインの中で確かめる。
    await waitFor(() =>
      expect(screen.getByRole("complementary", { name: "ノード情報と凡例" })).toHaveTextContent(
        "SYKT.D250718.ORDER.DAILY",
      ),
    );
    expect(screen.queryByRole("button", { name: /ソースを開く/ })).not.toBeInTheDocument();
  });

  it("凡例はノード 10 種・エッジ 5 種と破線の意味を示す", async () => {
    renderGraph(analyzedState());
    await waitForGraph();
    const detail = screen.getByRole("complementary", { name: "ノード情報と凡例" });
    expect(detail).toHaveTextContent("六角形");
    expect(detail).toHaveTextContent("外部ユーティリティ");
    expect(detail).toHaveTextContent("トランザクション遷移");
    expect(detail).toHaveTextContent("破線 ― データフロー由来・未解決");
  });
});

describe("GraphScreen の図の書出と再構築", () => {
  it("SVG 出力は engine の callgraph --svg を起動し、書かれたパスを示す", async () => {
    renderGraph(analyzedState());
    await waitForGraph();
    runCallgraph.mockResolvedValueOnce(
      callgraphResult({ outputs: { svg: "C:\\proj\\callgraph.svg" } }),
    );
    fireEvent.click(screen.getByRole("button", { name: "SVG 出力" }));
    await waitFor(() =>
      expect(screen.getByTestId("toast")).toHaveTextContent("C:\\proj\\callgraph.svg"),
    );
    expect(runCallgraph).toHaveBeenLastCalledWith({
      inputDir: INPUT_DIR,
      copybookPaths: ["C:\\資産\\copy"],
      db: "C:\\proj\\cobol-insight.db",
      svgFile: "C:\\proj\\callgraph.svg",
    });
  });

  it("PNG 出力の失敗は理由を示す", async () => {
    renderGraph(analyzedState());
    await waitForGraph();
    runCallgraph.mockRejectedValueOnce(new Error("graphviz の描画に失敗"));
    fireEvent.click(screen.getByRole("button", { name: "PNG 出力" }));
    await waitFor(() => expect(screen.getByTestId("toast")).toHaveTextContent("graphviz の描画に失敗"));
  });

  it("再構築で callgraph を起動し直す", async () => {
    renderGraph(analyzedState());
    await waitForGraph();
    fireEvent.click(screen.getByRole("button", { name: "再構築" }));
    await waitFor(() => expect(runCallgraph).toHaveBeenCalledTimes(2));
  });
});

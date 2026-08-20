import { render, screen, waitFor } from "@testing-library/react";
import { describe, it, expect, beforeEach, vi } from "vitest";
import type { CobolInsightApi, EngineResult } from "../../../shared/engine-api";
import { Shell } from "../shell/Shell";
import {
  ProjectProvider,
  initialProjectState,
  type ProjectState,
} from "../state/projectStore";
import { SettingsProvider } from "../state/settingsStore";
import {
  WorkbenchProvider,
  initialWorkbenchState,
  singletonTab,
  type WorkbenchState,
} from "../state/workbenchStore";
import { FIXTURE_CATALOG } from "../data/__fixtures__/catalog";
import { SAMPLE_FINDINGS, SAMPLE_INVENTORY } from "../data/__fixtures__/samples";

/** Monaco は jsdom では動かない。差分の面は組み上がるところまでを見る。 */
vi.mock("../vendor/monacoEditor", () => ({
  monacoEditor: () => ({
    languages: {
      register: () => undefined,
      setMonarchTokensProvider: () => undefined,
      setLanguageConfiguration: () => undefined,
    },
    editor: {
      defineTheme: () => undefined,
      createModel: () => ({ dispose: () => undefined }),
      createDiffEditor: () => ({
        setModel: () => undefined,
        dispose: () => undefined,
      }),
    },
  }),
}));

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

const FIX_TAB: WorkbenchState = {
  ...initialWorkbenchState,
  tabs: [singletonTab("fix")],
  activeTabId: "fix",
};

/** engine の起動結果。要約だけを差し替えて使う。 */
function engineResult(summary: Record<string, unknown> | null): EngineResult {
  return {
    subcommand: "fix-preview",
    exitCode: 0,
    summary,
    stdout: "",
    stderr: "",
    outputs: {},
  };
}

function stubApi(overrides: Partial<CobolInsightApi> = {}): void {
  const stub: Partial<CobolInsightApi> = {
    getOutputPaths: vi.fn().mockRejectedValue(new Error("試験では成果物の位置を持たない")),
    readSettings: vi.fn().mockRejectedValue(new Error("試験では設定を持たない")),
    ...overrides,
  };
  window.cobolInsight = stub as CobolInsightApi;
}

function renderShell(project: ProjectState): void {
  render(
    <SettingsProvider>
      <ProjectProvider initial={project}>
        <WorkbenchProvider initial={FIX_TAB}>
          <Shell />
        </WorkbenchProvider>
      </ProjectProvider>
    </SettingsProvider>,
  );
}

beforeEach(() => {
  stubApi();
});

describe("修正案のタブ", () => {
  it("解析していなければ、解析が先だと示す", () => {
    renderShell(initialProjectState);
    expect(screen.getByRole("region", { name: "修正案がありません" })).toBeInTheDocument();
  });

  it("解析済みなら修正案を生成し、対象が無ければその旨を示す", async () => {
    const runFixPreview = vi
      .fn()
      .mockResolvedValue(engineResult({ fixedFiles: [], fixCount: 0, analysisErrors: 0 }));
    stubApi({ runFixPreview });
    renderShell(ANALYZED);

    await waitFor(() => expect(runFixPreview).toHaveBeenCalledTimes(1));
    expect(
      await screen.findByRole("region", { name: "修正案は生成されませんでした" }),
    ).toBeInTheDocument();
  });

  it("修正案があれば一覧と差分を組む", async () => {
    const runFixPreview = vi.fn().mockResolvedValue(
      engineResult({
        fixedFiles: ["cobol/SYK007.cbl"],
        fixCount: 1,
        analysisErrors: 0,
      }),
    );
    const runFixApply = vi.fn().mockResolvedValue({
      ...engineResult({ writtenFiles: ["cobol/SYK007.cbl"], fixCount: 1, analysisErrors: 0 }),
      subcommand: "fix-apply" as const,
      outputs: { outDir: "C:\\out\\fix-preview" },
    });
    const readFixResult = vi.fn().mockResolvedValue({
      relPath: "cobol/SYK007.cbl",
      originalText: "       COMPUTE A = B + C.\n",
      fixedText: "       COMPUTE A = B + C\n         ON SIZE ERROR ...\n",
    });
    stubApi({ runFixPreview, runFixApply, readFixResult });
    renderShell(ANALYZED);

    expect(await screen.findByRole("listbox", { name: "修正案の一覧" })).toBeInTheDocument();
    await waitFor(() => expect(readFixResult).toHaveBeenCalledTimes(1));
    expect(
      screen.getByRole("group", { name: "修正案の差分 cobol/SYK007.cbl" }),
    ).toBeInTheDocument();
  });
});

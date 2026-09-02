import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { fireEvent } from "@testing-library/dom";
import type { CobolInsightApi } from "../../../shared/ipc";
import { emptyAppSettings } from "../../../shared/settings";
import { emptyRulesFile } from "../../../shared/rulesFile";
import { App } from "../App";

const OUTPUT_PATHS = {
  db: "C:/data/p.db",
  sarif: "C:/data/lint.sarif",
  sqlSarif: "C:/data/sql.sarif",
  copyExpansion: "C:/data/copy.json",
  rules: "C:/data/rules.json",
};

const INVENTORY = [
  { id: 1, path: "cobol/SYK001.cbl", name: "SYK001.cbl", type: "PROGRAM", codepage: "Shift_JIS", byteSize: 100, findingCount: 1 },
  { id: 2, path: "jcl/SYKD010.jcl", name: "SYKD010.jcl", type: "JCL", codepage: "Shift_JIS", byteSize: 80, findingCount: 0 },
];

const FINDINGS = [
  { ruleId: "R001", level: "error", message: "未初期化", file: "cobol/SYK001.cbl", startLine: 10, startColumn: 1 },
];

function fakeApi(overrides: Partial<CobolInsightApi> = {}): CobolInsightApi {
  return {
    run: vi.fn(async (invocation) => ({
      subcommand: invocation.subcommand,
      exitCode: 0,
      summary: null,
      stdout: "",
      stderr: "",
      outputs: {},
    })),
    cancel: vi.fn(async () => undefined),
    decode: vi.fn(async () => ({
      text: "000100 IDENTIFICATION DIVISION.\n000200 PROGRAM-ID. SYK001.\n",
      codepage: "Shift_JIS",
      detected: true,
      soSiPresent: false,
      lines: [],
      stamp: { mtimeMs: 0, byteSize: 0 },
      error: "",
    })),
    save: vi.fn(),
    rules: vi.fn(async () => ({ rules: [], ruleErrors: [] })),
    validateRules: vi.fn(),
    readInventory: vi.fn(async () => INVENTORY),
    readSarif: vi.fn(async (path: string) => (path === OUTPUT_PATHS.sarif ? FINDINGS : [])),
    readGraph: vi.fn(),
    readCopyExpansion: vi.fn(async () => ({ programs: [] })),
    readFixDiff: vi.fn(),
    readTranspile: vi.fn(),
    readReport: vi.fn(),
    outputPaths: vi.fn(async () => OUTPUT_PATHS),
    selectFolder: vi.fn(async () => "C:/assets"),
    dirExists: vi.fn(async () => true),
    stat: vi.fn(async () => null),
    importSource: vi.fn(),
    readSettings: vi.fn(async () => emptyAppSettings()),
    writeSettings: vi.fn(async () => undefined),
    readRules: vi.fn(async () => emptyRulesFile()),
    writeRules: vi.fn(),
    versions: { chrome: "0", node: "0", electron: "0" },
    ...overrides,
  } as CobolInsightApi;
}

function install(api: CobolInsightApi): void {
  Object.defineProperty(window, "cobolInsight", { value: api, configurable: true });
}

beforeEach(() => {
  install(fakeApi());
});

describe("the shell", () => {
  it("is built from the four regions", async () => {
    render(<App />);
    expect(await screen.findByTestId("activitybar")).toBeInTheDocument();
    expect(screen.getByTestId("sidepanel")).toBeInTheDocument();
    expect(screen.getByTestId("editorarea")).toBeInTheDocument();
    expect(screen.getByTestId("bottompanel")).toBeInTheDocument();
  });

  it("shows the welcome view while no tab is open", async () => {
    render(<App />);
    expect(await screen.findByTestId("welcome")).toBeInTheDocument();
  });
});

describe("choosing a folder", () => {
  it("runs the scan alone and fills the tree with kind badges", async () => {
    const api = fakeApi();
    install(api);
    render(<App />);

    fireEvent.click(await screen.findByTestId("welcome-select-folder"));

    const row = await screen.findByTestId("tree-cobol/SYK001.cbl");
    expect(row.textContent).toContain("SYK001.cbl");
    expect(row.querySelector(".ci-badge")).not.toBeNull();
    expect(await screen.findByTestId("tree-jcl/SYKD010.jcl")).toBeInTheDocument();

    const subcommands = (api.run as ReturnType<typeof vi.fn>).mock.calls.map(
      (call) => (call[0] as { subcommand: string }).subcommand,
    );
    expect(subcommands).toEqual(["scan"]);
  });

  it("runs the three stages when the analysis is started", async () => {
    const api = fakeApi();
    install(api);
    render(<App />);

    fireEvent.click(await screen.findByTestId("welcome-select-folder"));
    await screen.findByTestId("tree-cobol/SYK001.cbl");
    // The run button is absent while the scan is in progress, so this waits for the scan to end.
    fireEvent.click(await screen.findByTestId("run-analysis"));

    await waitFor(() =>
      expect(
        (api.run as ReturnType<typeof vi.fn>).mock.calls.map(
          (call) => (call[0] as { subcommand: string }).subcommand,
        ),
      ).toEqual(["scan", "scan", "lint", "sql-lint"]),
    );
  });

  it("passes the one rule file to every stage", async () => {
    const api = fakeApi();
    install(api);
    render(<App />);
    fireEvent.click(await screen.findByTestId("welcome-select-folder"));
    await screen.findByTestId("tree-cobol/SYK001.cbl");

    for (const call of (api.run as ReturnType<typeof vi.fn>).mock.calls) {
      const invocation = call[0] as { request: { rulesFile?: string } };
      expect(invocation.request.rulesFile).toBe(OUTPUT_PATHS.rules);
    }
  });
});

describe("the problems panel", () => {
  it("opens the asset's tab when a row is chosen", async () => {
    render(<App />);
    fireEvent.click(await screen.findByTestId("welcome-select-folder"));
    // Opening the folder only scans it; the findings come from the analysis.
    await screen.findByTestId("tree-cobol/SYK001.cbl");
    fireEvent.click(await screen.findByTestId("run-analysis"));

    const row = await screen.findByTestId("finding-lint:0");
    fireEvent.click(row);

    expect(await screen.findByTestId("tab-source:cobol/SYK001.cbl")).toBeInTheDocument();
    expect(screen.getByTestId("tabpanel-source:cobol/SYK001.cbl")).toBeInTheDocument();
  });
});

describe("the command palette", () => {
  it("opens on Ctrl+Shift+P, filters as you type, and runs on Enter", async () => {
    render(<App />);
    await screen.findByTestId("activitybar");
    expect(screen.getByTestId("bottompanel")).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "P", ctrlKey: true, shiftKey: true });
    const input = await screen.findByTestId("command-palette-input");

    fireEvent.change(input, { target: { value: "パネル" } });
    expect(screen.getByTestId("command-view.togglePanel")).toBeInTheDocument();
    expect(screen.queryByTestId("command-view.toggleSideBar")).toBeNull();

    fireEvent.keyDown(input, { key: "Enter" });
    await waitFor(() => expect(screen.queryByTestId("command-palette")).toBeNull());
    expect(screen.queryByTestId("bottompanel")).toBeNull();
  });

  it("closes on Escape without running anything", async () => {
    render(<App />);
    fireEvent.keyDown(window, { key: "P", ctrlKey: true, shiftKey: true });
    const input = await screen.findByTestId("command-palette-input");
    fireEvent.keyDown(input, { key: "Escape" });
    await waitFor(() => expect(screen.queryByTestId("command-palette")).toBeNull());
    expect(screen.getByTestId("bottompanel")).toBeInTheDocument();
  });

  it("says so when nothing matches", async () => {
    render(<App />);
    fireEvent.keyDown(window, { key: "P", ctrlKey: true, shiftKey: true });
    fireEvent.change(await screen.findByTestId("command-palette-input"), {
      target: { value: "zzzz" },
    });
    expect(screen.getByTestId("command-palette-empty")).toBeInTheDocument();
  });
});

describe("closing a tab", () => {
  it("closes a clean tab without asking", async () => {
    render(<App />);
    fireEvent.click(await screen.findByTestId("welcome-select-folder"));
    fireEvent.click(await screen.findByTestId("tree-cobol/SYK001.cbl"));

    const tabId = "source:cobol/SYK001.cbl";
    expect(await screen.findByTestId(`tab-${tabId}`)).toBeInTheDocument();
    fireEvent.click(screen.getByTestId(`close-${tabId}`));
    await waitFor(() => expect(screen.queryByTestId(`tab-${tabId}`)).toBeNull());
    expect(screen.queryByTestId("confirm-discard")).toBeNull();
  });
});

describe("keyboard chords", () => {
  it("toggles the side bar with Ctrl+B", async () => {
    render(<App />);
    expect(await screen.findByTestId("sidepanel")).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "b", ctrlKey: true });
    await waitFor(() => expect(screen.queryByTestId("sidepanel")).toBeNull());
  });

  it("toggles the panel with Ctrl+J", async () => {
    render(<App />);
    expect(await screen.findByTestId("bottompanel")).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "j", ctrlKey: true });
    await waitFor(() => expect(screen.queryByTestId("bottompanel")).toBeNull());
  });
});

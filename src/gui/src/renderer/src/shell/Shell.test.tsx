import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { fireEvent } from "@testing-library/dom";
import type { CobolInsightApi } from "../../../shared/ipc";
import { emptyAppSettings } from "../../../shared/settings";
import { emptyRulesFile } from "../../../shared/rulesFile";
import { text } from "../i18n/text";
import { App } from "../App";

const OUTPUT_PATHS = {
  db: "C:/data/p.db",
  sarif: "C:/data/lint.sarif",
  sqlSarif: "C:/data/sql.sarif",
  scopedSarif: "C:/data/scope.sarif",
  scopedSqlSarif: "C:/data/scope-sql.sarif",
  copyExpansion: "C:/data/copy.json",
  rules: "C:/data/rules.json",
};

const INVENTORY = [
  { id: 1, path: "cobol/SYK001.cbl", name: "SYK001.cbl", type: "PROGRAM", codepage: "Shift_JIS", findingCount: 1 },
  { id: 2, path: "jcl/SYKD010.jcl", name: "SYKD010.jcl", type: "JCL", codepage: "Shift_JIS", findingCount: 0 },
  { id: 3, path: "copybook/SYKCPY1.cpy", name: "SYKCPY1.cpy", type: "COPYBOOK", codepage: "Shift_JIS", findingCount: 1 },
];

// A copybook the scoped program expands: the engine reports it under a scope that does not name it.
const FINDINGS = [
  { ruleId: "R026", level: "error", message: "パスワードの直書き", file: "copybook/SYKCPY1.cpy", startLine: 10, startColumn: 1 },
];

function fakeApi(overrides: Partial<CobolInsightApi> = {}): CobolInsightApi {
  return {
    run: vi.fn(async (invocation) => ({
      subcommand: invocation.subcommand,
      exitCode: 0,
      // A finished run prints its summary; a run without one counts as a crashed engine.
      summary: { findingCount: 0 },
      stdout: "",
      stderr: "",
      outputs: {},
    })),
    cancel: vi.fn(async () => undefined),
    decode: vi.fn(async () => ({
      text: "000100 IDENTIFICATION DIVISION.\n000200 PROGRAM-ID. SYK001.\n",
      codepage: "Shift_JIS",
      detected: true,
      lines: [],
      stamp: { mtimeMs: 0, byteSize: 0 },
      error: "",
    })),
    save: vi.fn(),
    rules: vi.fn(async () => ({ rules: [], ruleErrors: [] })),
    validateRules: vi.fn(),
    readInventory: vi.fn(async () => INVENTORY),
    // A scoped run writes and reads a pair of its own, so both lint destinations carry the findings.
    readSarif: vi.fn(async (path: string) =>
      path === OUTPUT_PATHS.sarif || path === OUTPUT_PATHS.scopedSarif ? FINDINGS : [],
    ),
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

  it("analyses the selected asset with lint alone, scoped to it", async () => {
    const api = fakeApi();
    install(api);
    render(<App />);

    fireEvent.click(await screen.findByTestId("welcome-select-folder"));
    // Choosing a row in the tree is what settles the target of the run.
    fireEvent.click(await screen.findByTestId("tree-cobol/SYK001.cbl"));
    // The run button is absent while the scan is in progress, so this waits for the scan to end.
    fireEvent.click(await screen.findByTestId("run-analysis"));

    const calls = api.run as ReturnType<typeof vi.fn>;
    // The folder was scanned when it was opened, so the run is the lint stage alone.
    await waitFor(() =>
      expect(calls.mock.calls.map((call) => (call[0] as { subcommand: string }).subcommand)).toEqual(
        ["scan", "lint"],
      ),
    );
    const lint = calls.mock.calls[1][0] as { request: { scope?: string[] } };
    expect(lint.request.scope).toEqual(["cobol/SYK001.cbl"]);
  });

  it("asks for a target when the run is started with nothing selected", async () => {
    const api = fakeApi();
    install(api);
    render(<App />);

    fireEvent.click(await screen.findByTestId("welcome-select-folder"));
    await screen.findByTestId("tree-cobol/SYK001.cbl");
    fireEvent.click(await screen.findByTestId("run-analysis"));

    expect(await screen.findByText(text.run.noSelection)).toBeInTheDocument();
    expect(
      (api.run as ReturnType<typeof vi.fn>).mock.calls.map(
        (call) => (call[0] as { subcommand: string }).subcommand,
      ),
    ).toEqual(["scan"]);
  });

  it("refuses a copybook as the target, since it is analysed through the programs that copy it", async () => {
    const api = fakeApi();
    install(api);
    render(<App />);

    fireEvent.click(await screen.findByTestId("welcome-select-folder"));
    fireEvent.click(await screen.findByTestId("tree-copybook/SYKCPY1.cpy"));
    fireEvent.click(await screen.findByTestId("run-analysis"));

    expect(await screen.findByText(text.run.copybookSelection)).toBeInTheDocument();
    // A scope naming only a copybook parses no program, and its empty result would delete the
    // findings the copybook has.
    expect(
      (api.run as ReturnType<typeof vi.fn>).mock.calls.map(
        (call) => (call[0] as { subcommand: string }).subcommand,
      ),
    ).toEqual(["scan"]);
  });

  it("refuses a folder holding copybooks alone, whose scope names no program either", async () => {
    const api = fakeApi();
    install(api);
    render(<App />);

    fireEvent.click(await screen.findByTestId("welcome-select-folder"));
    fireEvent.click(await screen.findByTestId("tree-copybook"));
    fireEvent.click(await screen.findByTestId("run-analysis"));

    expect(await screen.findByText(text.run.copybookSelection)).toBeInTheDocument();
    expect(
      (api.run as ReturnType<typeof vi.fn>).mock.calls.map(
        (call) => (call[0] as { subcommand: string }).subcommand,
      ),
    ).toEqual(["scan"]);
  });

  it("names what the run button will analyse, and offers the whole folder beside it", async () => {
    const api = fakeApi();
    install(api);
    render(<App />);

    fireEvent.click(await screen.findByTestId("welcome-select-folder"));
    fireEvent.click(await screen.findByTestId("tree-cobol/SYK001.cbl"));
    expect((await screen.findByTestId("run-target")).textContent).toBe(
      text.app.runTarget("cobol/SYK001.cbl", "file"),
    );

    // The second control ignores the selection, so it is the way in when nothing is selected.
    fireEvent.click(screen.getByTestId("run-analysis-all"));
    await waitFor(() =>
      expect(
        (api.run as ReturnType<typeof vi.fn>).mock.calls.map(
          (call) => (call[0] as { subcommand: string }).subcommand,
        ),
      ).toEqual(["scan", "scan", "lint"]),
    );
  });

  it("analyses the whole folder from the welcome screen, where nothing is selected", async () => {
    const api = fakeApi();
    install(api);
    render(<App />);

    fireEvent.click(await screen.findByTestId("welcome-select-folder"));
    fireEvent.click(await screen.findByTestId("welcome-run"));

    await waitFor(() =>
      expect(
        (api.run as ReturnType<typeof vi.fn>).mock.calls.map(
          (call) => (call[0] as { subcommand: string }).subcommand,
        ),
      ).toEqual(["scan", "scan", "lint"]),
    );
  });

  it("runs both stages over the whole folder on the command that says so", async () => {
    const api = fakeApi();
    install(api);
    render(<App />);

    fireEvent.click(await screen.findByTestId("welcome-select-folder"));
    await screen.findByTestId("tree-cobol/SYK001.cbl");
    fireEvent.keyDown(window, { key: "P", ctrlKey: true, shiftKey: true });
    fireEvent.click(await screen.findByTestId("command-run.analyzeAll"));

    await waitFor(() =>
      expect(
        (api.run as ReturnType<typeof vi.fn>).mock.calls.map(
          (call) => (call[0] as { subcommand: string }).subcommand,
        ),
      ).toEqual(["scan", "scan", "lint"]),
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
    // Opening the folder only scans it; the findings come from the analysis, which runs over the
    // program chosen in the tree. The finding below names the copybook that program expands, which
    // a scope over the program reports on too.
    fireEvent.click(await screen.findByTestId("tree-cobol/SYK001.cbl"));
    fireEvent.click(await screen.findByTestId("run-analysis"));

    const row = await screen.findByTestId("finding-lint:copybook/SYKCPY1.cpy:10:R026:0");
    fireEvent.click(row);

    expect(await screen.findByTestId("tab-source:copybook/SYKCPY1.cpy")).toBeInTheDocument();
    expect(screen.getByTestId("tabpanel-source:copybook/SYKCPY1.cpy")).toBeInTheDocument();
  });

  it("keeps the detail on the chosen finding when the next run lists the findings in another order", async () => {
    const chosen = { ...FINDINGS[0], ruleId: "R014", message: "未使用の段落です", startLine: 40 };
    const fresh = { ...FINDINGS[0], ruleId: "R032", message: "けたが合いません", startLine: 60 };
    let lintReads = 0;
    const api = fakeApi({
      readSarif: vi.fn(async (path: string) => {
        if (path !== OUTPUT_PATHS.sarif && path !== OUTPUT_PATHS.scopedSarif) {
          return [];
        }
        lintReads += 1;
        // The engine writes the findings in whatever order it detects them, which a re-run changes.
        return lintReads === 1 ? [FINDINGS[0], chosen] : [chosen, fresh, FINDINGS[0]];
      }),
    });
    install(api);
    render(<App />);

    fireEvent.click(await screen.findByTestId("welcome-select-folder"));
    fireEvent.click(await screen.findByTestId("tree-cobol/SYK001.cbl"));
    fireEvent.click(await screen.findByTestId("run-analysis"));

    // Chosen by what the row says, so the row's own identity is what the assertion rests on.
    fireEvent.click(await screen.findByText(chosen.message));
    expect((await screen.findByTestId("problem-detail")).textContent).toContain(chosen.message);

    fireEvent.click(await screen.findByTestId("run-analysis"));
    // The head row and the three findings the second run reports.
    await waitFor(() => expect(screen.getAllByRole("row")).toHaveLength(4));
    expect(screen.getByTestId("problem-detail").textContent).toContain(chosen.message);
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
    const row = screen.getByTestId("command-view.togglePanel");
    expect(row).toBeInTheDocument();
    expect(screen.queryByTestId("command-view.toggleSideBar")).toBeNull();
    // Every bound command shows its chord at the right of the row.
    expect(row.textContent).toContain("Ctrl+J");

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

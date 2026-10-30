import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { fireEvent } from "@testing-library/dom";
import type { CobolInsightApi, EngineInvocation, EngineResult } from "../../../shared/ipc";
import { emptyAppSettings } from "../../../shared/settings";
import { emptyRulesFile } from "../../../shared/rulesFile";
import { text } from "../i18n/text";
import { App } from "../App";
import { forgetDocument } from "../model/openDocuments";
import { disposeModel, existingModel } from "../vendor/monacoModels";

// The diff in the conflict dialog; the flag makes it throw while it renders in the case at hand.
const diff = vi.hoisted(() => ({ throws: false }));
vi.mock("../editors/diff/DiffView", () => ({
  DiffView: () => {
    if (diff.throws) throw new Error("the diff broke");
    return <div data-testid="diff-view" />;
  },
}));

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
    save: vi.fn(async (request) => ({
      written: true,
      path: request.path,
      reparseErrors: [],
      error: "",
      exitCode: 0,
    })),
    rules: vi.fn(async () => ({ rules: [], ruleErrors: [] })),
    validateRules: vi.fn(),
    readInventory: vi.fn(async () => INVENTORY),
    // A scoped run writes and reads a pair of its own, so both lint destinations carry the findings.
    readSarif: vi.fn(async (path: string) =>
      path === OUTPUT_PATHS.sarif || path === OUTPUT_PATHS.scopedSarif ? FINDINGS : [],
    ),
    readGraph: vi.fn(),
    readProgramFlow: vi.fn(async () => ({ paragraphs: [], edges: [] })),
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
  diff.throws = false;
});

const EDITED_PATH = "cobol/SYK001.cbl";
const EDITED_TAB = `source:${EDITED_PATH}`;

/** Opens the folder and one asset, and types into it, so its tab holds an unsaved edit. */
async function openEdited(): Promise<void> {
  disposeModel(EDITED_TAB);
  forgetDocument(EDITED_TAB);
  render(<App />);
  fireEvent.click(await screen.findByTestId("welcome-select-folder"));
  fireEvent.click(await screen.findByTestId(`tree-${EDITED_PATH}`));
  await waitFor(() => expect(existingModel(EDITED_TAB)).not.toBeNull());
  const model = existingModel(EDITED_TAB) as unknown as { edit(text: string): void };
  act(() => model.edit("EDITED"));
  await screen.findByTestId(`dirty-${EDITED_TAB}`);
}

describe("App", () => {
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
    it("fills the tree with kind badges after the scan", async () => {
      const api = fakeApi();
      install(api);
      render(<App />);

      fireEvent.click(await screen.findByTestId("welcome-select-folder"));

      const row = await screen.findByTestId("tree-cobol/SYK001.cbl");
      expect(row.textContent).toContain("SYK001.cbl");
      expect(row.querySelector(".ci-badge")).not.toBeNull();
      expect(await screen.findByTestId("tree-jcl/SYKD010.jcl")).toBeInTheDocument();
    });

    it("runs the scan alone when a folder is selected", async () => {
      const api = fakeApi();
      install(api);
      render(<App />);

      fireEvent.click(await screen.findByTestId("welcome-select-folder"));
      await screen.findByTestId("tree-cobol/SYK001.cbl");

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

    it("keeps the run button disabled while nothing is selected", async () => {
      const api = fakeApi();
      install(api);
      render(<App />);

      fireEvent.click(await screen.findByTestId("welcome-select-folder"));
      await screen.findByTestId("tree-cobol/SYK001.cbl");
      const run = await screen.findByTestId("run-analysis");
      expect(run).toBeDisabled();
      fireEvent.click(run);

      expect(
        (api.run as ReturnType<typeof vi.fn>).mock.calls.map(
          (call) => (call[0] as { subcommand: string }).subcommand,
        ),
      ).toEqual(["scan"]);
    });

    it("refuses a copybook as the run target", async () => {
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

    it("refuses a folder of copybooks as the run target", async () => {
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

    it("names the run target in the title bar", async () => {
      const api = fakeApi();
      install(api);
      render(<App />);

      fireEvent.click(await screen.findByTestId("welcome-select-folder"));
      fireEvent.click(await screen.findByTestId("tree-cobol/SYK001.cbl"));
      expect((await screen.findByTestId("run-target")).textContent).toBe(
        text.app.runTarget("cobol/SYK001.cbl", "file"),
      );
    });

    it("analyses the whole folder from run-analysis-all while a file is selected", async () => {
      const api = fakeApi();
      install(api);
      render(<App />);

      fireEvent.click(await screen.findByTestId("welcome-select-folder"));
      fireEvent.click(await screen.findByTestId("tree-cobol/SYK001.cbl"));
      await screen.findByTestId("run-target");

      // run-analysis-all ignores the selection, so it is the way in when nothing is selected.
      fireEvent.click(screen.getByTestId("run-analysis-all"));
      await waitFor(() =>
        expect(
          (api.run as ReturnType<typeof vi.fn>).mock.calls.map(
            (call) => (call[0] as { subcommand: string }).subcommand,
          ),
        ).toEqual(["scan", "scan", "lint"]),
      );
    });

    it("analyses the whole folder from the welcome screen", async () => {
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

    it("starts no second scan for a folder chosen while a run is in progress", async () => {
      let chooseOther: (folder: string) => void = () => undefined;
      const other = new Promise<string>((done) => {
        chooseOther = done;
      });
      let endLint: () => void = () => undefined;
      const lintEnds = new Promise<void>((done) => {
        endLint = done;
      });
      const api = fakeApi({
        selectFolder: vi.fn().mockResolvedValueOnce("C:/assets").mockReturnValueOnce(other),
        run: vi.fn(async (invocation: EngineInvocation) => {
          if (invocation.subcommand === "lint") {
            await lintEnds;
          }
          return {
            subcommand: invocation.subcommand,
            exitCode: 0,
            summary: { findingCount: 0 },
            stdout: "",
            stderr: "",
            outputs: {},
          };
        }),
      });
      install(api);
      render(<App />);
      fireEvent.click(await screen.findByTestId("welcome-select-folder"));
      await screen.findByTestId("tree-cobol/SYK001.cbl");
      const calls = api.run as ReturnType<typeof vi.fn>;
      await waitFor(() => expect(calls).toHaveBeenCalledTimes(1));

      // The dialog is opened before the run starts and answered while it runs.
      fireEvent.click(screen.getByTestId("select-folder"));
      fireEvent.click(screen.getByTestId("run-analysis-all"));
      await waitFor(() => expect(calls).toHaveBeenCalledTimes(3));
      await act(async () => chooseOther("C:/other"));

      expect(await screen.findByText(text.run.folderWhileRunning)).toBeInTheDocument();
      expect(calls.mock.calls.map((call) => (call[0] as EngineInvocation).subcommand)).toEqual([
        "scan",
        "scan",
        "lint",
      ]);
      await act(async () => {
        endLint();
        await lintEnds;
      });
    });

    it("passes the one rule file to every stage", async () => {
      const api = fakeApi();
      install(api);
      render(<App />);
      fireEvent.click(await screen.findByTestId("welcome-select-folder"));
      await screen.findByTestId("tree-cobol/SYK001.cbl");

      const calls = (api.run as ReturnType<typeof vi.fn>).mock.calls;
      expect(calls.length).toBeGreaterThan(0);
      for (const call of calls) {
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

    it("keeps the chosen finding's detail when a rerun reorders the findings", async () => {
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

      // The finding opened the copybook's tab, which the run target follows; the program is chosen
      // again so the second run covers the same scope as the first.
      fireEvent.click(screen.getByTestId("tree-cobol/SYK001.cbl"));
      fireEvent.click(await screen.findByTestId("run-analysis"));
      // The head row and the three findings the second run reports.
      await waitFor(() => expect(screen.getAllByRole("row")).toHaveLength(4));
      expect(screen.getByTestId("problem-detail").textContent).toContain(chosen.message);
    });
  });

  describe("the command palette", () => {
    async function filterPalette(): Promise<HTMLElement> {
      render(<App />);
      await screen.findByTestId("activitybar");
      fireEvent.keyDown(window, { key: "P", ctrlKey: true, shiftKey: true });
      const input = await screen.findByTestId("command-palette-input");
      fireEvent.change(input, { target: { value: "パネル" } });
      return input;
    }

    it("filters the commands by the typed text", async () => {
      await filterPalette();
      expect(screen.getByTestId("command-view.togglePanel")).toBeInTheDocument();
      expect(screen.queryByTestId("command-view.toggleSideBar")).toBeNull();
    });

    it("shows the bound chord on a command row", async () => {
      await filterPalette();
      // Every bound command shows its chord at the right of the row.
      expect(screen.getByTestId("command-view.togglePanel").textContent).toContain("Ctrl+J");
    });

    it("runs the chosen command on Enter", async () => {
      const input = await filterPalette();
      expect(screen.getByTestId("bottompanel")).toBeInTheDocument();

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

    it("do nothing while a dialog is open", async () => {
      render(<App />);
      expect(await screen.findByTestId("sidepanel")).toBeInTheDocument();
      fireEvent.keyDown(window, { key: "P", ctrlKey: true, shiftKey: true });
      await screen.findByTestId("command-palette-input");

      fireEvent.keyDown(window, { key: "b", ctrlKey: true });
      fireEvent.keyDown(window, { key: "j", ctrlKey: true });

      expect(screen.getByTestId("sidepanel")).toBeInTheDocument();
      expect(screen.getByTestId("bottompanel")).toBeInTheDocument();
    });

    it("rest while the dialog asking about an unsaved tab is open", async () => {
      await openEdited();
      fireEvent.click(screen.getByTestId(`close-${EDITED_TAB}`));
      await screen.findByTestId("confirm-discard");

      fireEvent.keyDown(window, { key: "b", ctrlKey: true });
      fireEvent.keyDown(window, { key: "j", ctrlKey: true });

      expect(screen.getByTestId("sidepanel")).toBeInTheDocument();
      expect(screen.getByTestId("bottompanel")).toBeInTheDocument();
      expect(screen.getByTestId("confirm-discard")).toBeInTheDocument();
    });
  });

  describe("the focus after a dialog raised from the command palette", () => {
    it("goes to the tab in front, the palette that raised it being gone", async () => {
      await openEdited();
      fireEvent.keyDown(window, { key: "P", ctrlKey: true, shiftKey: true });
      fireEvent.click(await screen.findByTestId("command-editor.closeTab"));
      await screen.findByTestId("confirm-discard");

      fireEvent.click(screen.getByTestId("confirm-discard-no"));

      await waitFor(() => expect(screen.queryByTestId("confirm-discard")).toBeNull());
      expect(document.activeElement).toBe(document.getElementById(`tabheader-${EDITED_TAB}`));
    });
  });

  describe("the tabs of the bottom panel", () => {
    it("move the focus to the tab an arrow key selected", async () => {
      render(<App />);
      const tabs = within(await screen.findByTestId("bottompanel")).getByRole("tablist");
      screen.getByTestId("panel-tab-problems").focus();

      fireEvent.keyDown(tabs, { key: "ArrowRight" });
      expect(screen.getByTestId("panel-tab-output")).toHaveAttribute("aria-selected", "true");
      expect(screen.getByTestId("panel-tab-output")).toHaveFocus();

      fireEvent.keyDown(tabs, { key: "ArrowLeft" });
      expect(screen.getByTestId("panel-tab-problems")).toHaveAttribute("aria-selected", "true");
      expect(screen.getByTestId("panel-tab-problems")).toHaveFocus();
    });

    it("point only from the selected tab to the panel, which the selected tab names", async () => {
      render(<App />);
      const panel = within(await screen.findByTestId("bottompanel")).getByRole("tabpanel");

      expect(screen.getByTestId("panel-tab-problems")).toHaveAttribute("aria-controls", panel.id);
      expect(screen.getByTestId("panel-tab-output")).not.toHaveAttribute("aria-controls");
      expect(panel).toHaveAccessibleName(text.panel.problems);

      fireEvent.click(screen.getByTestId("panel-tab-output"));
      const shown = within(screen.getByTestId("bottompanel")).getByRole("tabpanel");
      expect(screen.getByTestId("panel-tab-output")).toHaveAttribute("aria-controls", shown.id);
      expect(screen.getByTestId("panel-tab-problems")).not.toHaveAttribute("aria-controls");
      expect(shown).toHaveAccessibleName(text.panel.output);
    });
  });

  describe("the scan that follows a write", () => {
    const FIRST = "cobol/SYK001.cbl";
    const SECOND = "jcl/SYKD010.jcl";

    // The text models and the decodes of the tabs outlive the render, so each case starts without them.
    beforeEach(() => {
      for (const path of [FIRST, SECOND]) {
        disposeModel(`source:${path}`);
        forgetDocument(`source:${path}`);
      }
    });

    const finished = (invocation: EngineInvocation): EngineResult => ({
      subcommand: invocation.subcommand,
      exitCode: 0,
      summary: { findingCount: 0 },
      stdout: "",
      stderr: "",
      outputs: {},
    });
    const subcommands = (api: CobolInsightApi): string[] =>
      (api.run as ReturnType<typeof vi.fn>).mock.calls.map((call) => (call[0] as EngineInvocation).subcommand);
    /** Long enough for an effect that was going to start another run to have done so. */
    const settle = (): Promise<void> => act(async () => void (await new Promise((done) => setTimeout(done, 60))));

    /** Opens the folder and a tab for the asset, and waits until the tab has its text model. */
    async function openAsset(path: string): Promise<void> {
      if (screen.queryByTestId(`tree-${path}`) === null) {
        fireEvent.click(await screen.findByTestId("welcome-select-folder"));
      }
      fireEvent.click(await screen.findByTestId(`tree-${path}`));
      await waitFor(() => expect(existingModel(`source:${path}`)).not.toBeNull());
    }

    /** Types into the open tab's text, which makes it hold an unsaved edit. */
    async function edit(path: string, next: string): Promise<void> {
      const model = existingModel(`source:${path}`) as unknown as { edit(text: string): void };
      act(() => model.edit(next));
      await screen.findByTestId(`dirty-source:${path}`);
    }

    it("runs one scan without lint after one save", async () => {
      const api = fakeApi();
      install(api);
      render(<App />);
      await openAsset(FIRST);
      await edit(FIRST, "EDITED ONE");

      fireEvent.keyDown(window, { key: "s", ctrlKey: true });

      await waitFor(() => expect(subcommands(api)).toEqual(["scan", "scan"]));
      await settle();
      expect(subcommands(api)).toEqual(["scan", "scan"]);
      expect(api.save).toHaveBeenCalledTimes(1);
    });

    it("is one scan for a save of two tabs", async () => {
      // Each write takes a moment, so a scan started after the first one would be seen.
      const api = fakeApi({
        save: vi.fn(async (request) => {
          await new Promise((done) => setTimeout(done, 30));
          return { written: true, path: request.path, reparseErrors: [], error: "", exitCode: 0 };
        }),
      });
      install(api);
      render(<App />);
      await openAsset(FIRST);
      await edit(FIRST, "EDITED ONE");
      await openAsset(SECOND);
      await edit(SECOND, "EDITED TWO");

      fireEvent.keyDown(window, { key: "P", ctrlKey: true, shiftKey: true });
      fireEvent.click(await screen.findByTestId("command-editor.saveAll"));

      await waitFor(() => expect(api.save).toHaveBeenCalledTimes(2));
      await waitFor(() => expect(subcommands(api)).toEqual(["scan", "scan"]));
      await settle();
      expect(subcommands(api)).toEqual(["scan", "scan"]);
    });

    it("scans a write made during a run once that run has finished", async () => {
      let endLint: () => void = () => undefined;
      const lintEnds = new Promise<void>((done) => {
        endLint = done;
      });
      const api = fakeApi({
        run: vi.fn(async (invocation: EngineInvocation) => {
          if (invocation.subcommand === "lint") {
            await lintEnds;
          }
          return finished(invocation);
        }),
      });
      install(api);
      render(<App />);
      await openAsset(FIRST);
      await edit(FIRST, "EDITED ONE");
      fireEvent.click(screen.getByTestId("run-analysis-all"));
      await waitFor(() => expect(subcommands(api)).toEqual(["scan", "scan", "lint"]));

      fireEvent.keyDown(window, { key: "s", ctrlKey: true });
      await waitFor(() => expect(api.save).toHaveBeenCalledTimes(1));
      await settle();
      // The lint is still running, so nothing else is started beside it.
      expect(subcommands(api)).toEqual(["scan", "scan", "lint"]);

      endLint();
      await waitFor(() => expect(subcommands(api)).toEqual(["scan", "scan", "lint", "scan"]));
      await settle();
      expect(subcommands(api)).toEqual(["scan", "scan", "lint", "scan"]);
    });

    it("starts a scan that failed again only after the next write", async () => {
      let scans = 0;
      const api = fakeApi({
        run: vi.fn(async (invocation: EngineInvocation) => {
          scans += invocation.subcommand === "scan" ? 1 : 0;
          // The second scan is the one that follows the first save.
          return scans === 2 ? { ...finished(invocation), exitCode: 1, summary: null } : finished(invocation);
        }),
      });
      install(api);
      render(<App />);
      await openAsset(FIRST);
      await edit(FIRST, "EDITED ONE");
      fireEvent.keyDown(window, { key: "s", ctrlKey: true });
      await waitFor(() => expect(subcommands(api)).toEqual(["scan", "scan"]));

      await settle();
      expect(subcommands(api)).toEqual(["scan", "scan"]);

      await edit(FIRST, "EDITED TWICE");
      fireEvent.keyDown(window, { key: "s", ctrlKey: true });
      await waitFor(() => expect(subcommands(api)).toEqual(["scan", "scan", "scan"]));
    });
  });

  describe("the conflict dialog", () => {
    const PATH = "cobol/SYK001.cbl";

    beforeEach(() => {
      disposeModel(`source:${PATH}`);
      forgetDocument(`source:${PATH}`);
    });

    it("keeps its actions usable when the diff inside it fails to draw", async () => {
      vi.spyOn(console, "error").mockImplementation(() => undefined);
      diff.throws = true;
      // The file on disk carries another stamp than the one decoded, so the save raises the conflict.
      install(fakeApi({ stat: vi.fn(async () => ({ mtimeMs: 5, byteSize: 5 })) }));
      render(<App />);
      fireEvent.click(await screen.findByTestId("welcome-select-folder"));
      fireEvent.click(await screen.findByTestId(`tree-${PATH}`));
      await waitFor(() => expect(existingModel(`source:${PATH}`)).not.toBeNull());
      const model = existingModel(`source:${PATH}`) as unknown as { edit(text: string): void };
      act(() => model.edit("EDITED"));
      await screen.findByTestId(`dirty-source:${PATH}`);

      fireEvent.keyDown(window, { key: "s", ctrlKey: true });
      const dialog = await screen.findByTestId("save-conflict");
      fireEvent.click(within(dialog).getByTestId("save-conflict-diff"));

      expect(await within(dialog).findByTestId("editor-failed")).toBeInTheDocument();
      fireEvent.click(within(dialog).getByTestId("save-conflict-cancel"));
      await waitFor(() => expect(screen.queryByTestId("save-conflict")).toBeNull());
      vi.restoreAllMocks();
    });
  });
});

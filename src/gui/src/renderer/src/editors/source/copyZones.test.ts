import { createElement, type ReactElement, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import type * as monacoApi from "monaco-editor/editor/editor.api";
import type { CobolInsightApi, CopyExpansion } from "../../../../shared/ipc";
import { ProjectProvider, initialProjectState, useProjectDispatch } from "../../state/projectStore";
import { text } from "../../i18n/text";
import {
  clearCopyExpansionCache,
  copyStatementLine,
  showCopyZones,
  useCopyZones,
} from "./copyZones";

const OUTER = "CPY/OUTER.cpy";
const INNER = "CPY/INNER.cpy";

/** The lines of one expansion: the outer copybook, a nested one for two lines, then the outer again. */
const EXPANSION: CopyExpansion = {
  copyStatementLine: 7,
  copybookName: "OUTER",
  copybookPath: OUTER,
  lines: [
    { copybookLine: 1, text: "       01  OUTER-A PIC X.", copybookPath: null },
    { copybookLine: 1, text: "       01  INNER-A PIC X.", copybookPath: INNER },
    { copybookLine: 2, text: "       01  INNER-B PIC X.", copybookPath: INNER },
    // Spelled as the expansion's own path, which is the same copybook as the null of the first line.
    { copybookLine: 2, text: "       01  OUTER-B PIC X.", copybookPath: OUTER },
  ],
};

/**
 * The editor members the zones use, answering for one view zone and recording what it was given and
 * which zones it was asked to remove. `lines` is the text the editor shows.
 */
function fakeEditor(lines: readonly string[] = []): {
  editor: monacoApi.editor.IStandaloneCodeEditor;
  zones: monacoApi.editor.IViewZone[];
  removed: string[];
} {
  const zones: monacoApi.editor.IViewZone[] = [];
  const removed: string[] = [];
  const disposable = { dispose: vi.fn() };
  const editor = {
    getModel: () => ({ getLinesContent: () => [...lines] }),
    createDecorationsCollection: (decorations: readonly { range: { startLineNumber: number } }[]) => ({
      getRange: (index: number) => decorations[index]?.range ?? null,
      clear: vi.fn(),
    }),
    changeViewZones: (callback: (accessor: unknown) => void) =>
      callback({
        addZone: (zone: monacoApi.editor.IViewZone) => {
          zones.push(zone);
          return `zone-${zones.length}`;
        },
        removeZone: (id: string) => {
          removed.push(id);
        },
        layoutZone: vi.fn(),
      }),
    onMouseDown: () => disposable,
    addAction: () => disposable,
    onDidLayoutChange: () => disposable,
    getContainerDomNode: () => document.createElement("div"),
    getLayoutInfo: () => ({ contentLeft: 60, lineNumbersLeft: 10, lineNumbersWidth: 30 }),
  };
  return { editor: editor as unknown as monacoApi.editor.IStandaloneCodeEditor, zones, removed };
}

/** The table a read answers: a fresh copy each time, as a read of a rewritten file gives. */
function table(): { programs: { path: string; expansions: CopyExpansion[] }[] } {
  return { programs: [{ path: "cobol/A.cbl", expansions: [structuredClone(EXPANSION)] }] };
}

const OUTPUT_PATHS = {
  db: "C:/data/p.db",
  sarif: "C:/data/lint.sarif",
  sqlSarif: "C:/data/sql.sarif",
  scopedSarif: "C:/data/scope.sarif",
  scopedSqlSarif: "C:/data/scope-sql.sarif",
  copyExpansion: "C:/data/copy.json",
  rules: "C:/data/rules.json",
};

/** The hook on a drawn editor, reading the table through `read`; hands back the project dispatch. */
function renderZones(read: ReturnType<typeof vi.fn>, path = "cobol/A.cbl") {
  clearCopyExpansionCache();
  Object.defineProperty(window, "cobolInsight", {
    value: { readCopyExpansion: read } as unknown as CobolInsightApi,
    configurable: true,
  });
  const fake = fakeEditor();
  const editorRef = { current: fake.editor };
  const wrapper = ({ children }: { children: ReactNode }): ReactElement =>
    createElement(ProjectProvider, { initial: { ...initialProjectState, outputPaths: OUTPUT_PATHS }, children });
  const view = renderHook(
    () => {
      useCopyZones(editorRef, path, true);
      return useProjectDispatch();
    },
    { wrapper },
  );
  return { ...fake, dispatch: () => view.result.current };
}

/** What useAnalysis does as a scan finishes: the cache is emptied, then the scan is recorded. */
function scanned(dispatch: ReturnType<typeof useProjectDispatch>): void {
  act(() => {
    clearCopyExpansionCache();
    dispatch({ type: "STRUCTURE_SCANNED", writes: 0, ok: true });
  });
}

describe("the place of a zone", () => {
  it("is the COPY statement of its copybook nearest the recorded line, as the text now stands", () => {
    const lines = [
      "000100 PROCEDURE DIVISION.",
      "000200     COPY OUTER.",
      ...Array.from({ length: 6 }, () => "000300     CONTINUE."),
      // Two lines were typed above the statement the scan recorded at line 7.
      "000400     COPY OUTER.",
    ];
    const { editor, zones } = fakeEditor(lines);

    showCopyZones(editor, [EXPANSION]);

    expect(zones[0].afterLineNumber).toBe(9);
  });

  it("stays at the recorded line where the text names the copybook nowhere", () => {
    expect(copyStatementLine(["000100     COPY OTHER."], "OUTER", 7)).toBe(7);
  });

  it("passes over a comment line that names the copybook", () => {
    const lines = ["000100*    COPY OUTER.", "000200     CONTINUE.", "000300     COPY 'OUTER' OF LIB."];
    expect(copyStatementLine(lines, "OUTER", 1)).toBe(3);
  });
});

describe("the collapsed zones", () => {
  it("are drawn collapsed again when the zones are drawn anew", () => {
    const collapsed = new Set<string>();
    const first = fakeEditor();
    const handle = showCopyZones(first.editor, [EXPANSION], collapsed);
    (first.zones[0].domNode.querySelector(".ci-copy__toggle") as HTMLButtonElement).click();
    handle.dispose();

    const second = fakeEditor();
    showCopyZones(second.editor, [EXPANSION], collapsed);

    expect(second.zones[0].heightInLines).toBe(1);
    expect(second.zones[0].domNode.querySelector<HTMLElement>(".ci-copy__lines")?.hidden).toBe(true);
  });

  it("stay collapsed after a scan reads the folder again", async () => {
    const { zones, dispatch } = renderZones(vi.fn(async () => table()), "cobol/A.cbl");
    await waitFor(() => expect(zones).toHaveLength(1));
    act(() => (zones[0].domNode.querySelector(".ci-copy__toggle") as HTMLButtonElement).click());

    scanned(dispatch());

    await waitFor(() => expect(zones).toHaveLength(2));
    expect(zones[1].heightInLines).toBe(1);
  });
});

describe("showCopyZones", () => {
  describe("the zone of a COPY expansion", () => {
    it("counts a label row at each change of copybook, besides the header and the lines", () => {
      const { editor, zones } = fakeEditor();

      showCopyZones(editor, [EXPANSION]);

      // 1 header + 4 lines + 2 labels: outer to inner, and inner to outer.
      expect(zones).toHaveLength(1);
      expect(zones[0].heightInLines).toBe(7);
      expect(zones[0].afterLineNumber).toBe(7);
    });

    it("draws as many path rows as the height counted", () => {
      const { editor, zones } = fakeEditor();

      showCopyZones(editor, [EXPANSION]);

      const rows = zones[0].domNode.querySelectorAll(".ci-copy__line");
      const labels = zones[0].domNode.querySelectorAll(".ci-copy__line--path");
      expect(Array.from(labels, (label) => label.textContent)).toEqual([INNER, OUTER]);
      // The height is the header row and every row drawn under it, so the two agree.
      expect(zones[0].heightInLines).toBe(1 + rows.length);
      expect(rows.length - labels.length).toBe(EXPANSION.lines.length);
      // The margin carries one number cell for each of those rows.
      expect(zones[0].marginDomNode?.querySelectorAll(".ci-copy__number")).toHaveLength(rows.length);
    });

    it("counts no label row for lines that all come from the one copybook", () => {
      const { editor, zones } = fakeEditor();
      const plain: CopyExpansion = {
        ...EXPANSION,
        lines: EXPANSION.lines.map((line) => ({ ...line, copybookPath: null })),
      };

      showCopyZones(editor, [plain]);

      expect(zones[0].heightInLines).toBe(5);
      expect(zones[0].domNode.querySelectorAll(".ci-copy__line--path")).toHaveLength(0);
    });
  });

  describe("the note in the header of a zone", () => {
    it("says what it is given, and goes once it is taken away", () => {
      const { editor, zones } = fakeEditor();
      const handle = showCopyZones(editor, [EXPANSION]);
      const note = zones[0].domNode.querySelector<HTMLElement>(".ci-copy__note");
      expect(note?.hidden).toBe(true);

      handle.setNote(text.copyExpansion.structureReading);
      expect(note?.hidden).toBe(false);
      expect(note?.textContent).toBe(text.copyExpansion.structureReading);

      handle.setNote(null);
      expect(note?.hidden).toBe(true);
    });
  });
});

describe("useCopyZones", () => {
  describe("a failed read of the table", () => {
    it("leaves the zones as drawn", async () => {
      const read = vi
        .fn()
        .mockResolvedValueOnce({ programs: [{ path: "cobol/B.cbl", expansions: [EXPANSION] }] })
        .mockRejectedValue(new Error("unreadable"));
      const { zones, removed, dispatch } = renderZones(read, "cobol/B.cbl");
      await waitFor(() => expect(zones).toHaveLength(1));

      scanned(dispatch());

      await waitFor(() => expect(read).toHaveBeenCalledTimes(2));
      expect(zones).toHaveLength(1);
      expect(removed).toEqual([]);
    });
  });

  describe("the COPY expansion of the tab in front", () => {
    it("is read again after a scan that read the folder", async () => {
      clearCopyExpansionCache();
      const readCopyExpansion = vi.fn(async () => ({ programs: [] }));
      Object.defineProperty(window, "cobolInsight", {
        value: { readCopyExpansion } as unknown as CobolInsightApi,
        configurable: true,
      });
      const project = {
        ...initialProjectState,
        outputPaths: {
          db: "C:/data/p.db",
          sarif: "C:/data/lint.sarif",
          sqlSarif: "C:/data/sql.sarif",
          scopedSarif: "C:/data/scope.sarif",
          scopedSqlSarif: "C:/data/scope-sql.sarif",
          copyExpansion: "C:/data/copy.json",
          rules: "C:/data/rules.json",
        },
      };
      const wrapper = ({ children }: { children: ReactNode }): ReactElement =>
        createElement(ProjectProvider, { initial: project, children });
      const { result } = renderHook(
        () => {
          useCopyZones({ current: null }, "cobol/A.cbl", false);
          return useProjectDispatch();
        },
        { wrapper },
      );
      await waitFor(() => expect(readCopyExpansion).toHaveBeenCalledTimes(1));

      // What useAnalysis does as a scan finishes: the cache is emptied, then the scan is recorded.
      act(() => {
        clearCopyExpansionCache();
        result.current({ type: "STRUCTURE_SCANNED", writes: 0, ok: true });
      });

      await waitFor(() => expect(readCopyExpansion).toHaveBeenCalledTimes(2));
    });

    it("says in each zone's header when a write waits for its scan, and when that scan failed", async () => {
      clearCopyExpansionCache();
      const readCopyExpansion = vi.fn(async () => ({
        programs: [{ path: "cobol/A.cbl", expansions: [EXPANSION] }],
      }));
      Object.defineProperty(window, "cobolInsight", {
        value: { readCopyExpansion } as unknown as CobolInsightApi,
        configurable: true,
      });
      const { editor, zones } = fakeEditor();
      const editorRef = { current: editor };
      // A write the scan after it has yet to read.
      const project = {
        ...initialProjectState,
        outputPaths: {
          db: "C:/data/p.db",
          sarif: "C:/data/lint.sarif",
          sqlSarif: "C:/data/sql.sarif",
          scopedSarif: "C:/data/scope.sarif",
          scopedSqlSarif: "C:/data/scope-sql.sarif",
          copyExpansion: "C:/data/copy.json",
          rules: "C:/data/rules.json",
        },
        structureStale: ["cobol/A.cbl"],
        staleWrites: 1,
      };
      const wrapper = ({ children }: { children: ReactNode }): ReactElement =>
        createElement(ProjectProvider, { initial: project, children });
      const { result } = renderHook(
        () => {
          useCopyZones(editorRef, "cobol/A.cbl", true);
          return useProjectDispatch();
        },
        { wrapper },
      );
      await waitFor(() => expect(zones).toHaveLength(1));
      const note = (): string | null | undefined =>
        zones[0].domNode.querySelector<HTMLElement>(".ci-copy__note")?.textContent;
      expect(note()).toBe(text.copyExpansion.structureReading);

      act(() => result.current({ type: "STRUCTURE_SCANNED", writes: 1, ok: false }));

      expect(note()).toBe(text.copyExpansion.structureUnread);
    });
  });
});

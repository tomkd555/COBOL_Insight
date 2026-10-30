import type { ReactElement, ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { CobolInsightApi, DecodeResult, SourceStamp } from "../../../../shared/ipc";
import { forgetDocument, openDocument } from "../../model/openDocuments";
import { EditorStatusProvider } from "../../state/editorStatusStore";
import {
  ProjectProvider,
  initialProjectState,
  useProjectDispatch,
  type ProjectState,
} from "../../state/projectStore";
import { SettingsProvider } from "../../state/settingsStore";
import { WorkbenchProvider, sourceTabId } from "../../state/workbenchStore";
import { disposeModel, existingModel } from "../../vendor/monacoModels";
import { liveEditors } from "../../vendor/monaco-editor.stub";
import { SourceEditor } from "./SourceEditor";

const A = "cobol/A.cbl";
const B = "cobol/B.cbl";
const READ: SourceStamp = { mtimeMs: 1, byteSize: 10 };
const CHANGED: SourceStamp = { mtimeMs: 2, byteSize: 10 };

const PROGRAM = [
  "000100 IDENTIFICATION DIVISION.",
  "000200 PROGRAM-ID. A.",
  "000300 PROCEDURE DIVISION.",
  "000400 MAIN-PROC.",
  "000500     GOBACK.",
].join("\n");

function decoded(stamp: SourceStamp, codepage = "Shift_JIS"): DecodeResult {
  return { text: PROGRAM, codepage, detected: true, lines: [], stamp, error: "" };
}

function install(decode: ReturnType<typeof vi.fn>): void {
  Object.defineProperty(window, "cobolInsight", {
    value: {
      decode,
      readProgramFlow: vi.fn(async () => ({ paragraphs: [], edges: [] })),
    } as unknown as CobolInsightApi,
    configurable: true,
  });
}

/** Hands the project dispatch to the test, which plays the shell's part. */
let dispatchProject: ReturnType<typeof useProjectDispatch> = () => undefined;
function Probe(): null {
  dispatchProject = useProjectDispatch();
  return null;
}

function providers(children: ReactNode, project: Partial<ProjectState> = {}): ReactElement {
  return (
    <SettingsProvider>
      <ProjectProvider initial={{ ...initialProjectState, inputDir: "C:/assets", ...project }}>
        <WorkbenchProvider>
          <EditorStatusProvider>
            <Probe />
            {children}
          </EditorStatusProvider>
        </WorkbenchProvider>
      </ProjectProvider>
    </SettingsProvider>
  );
}

function editor(path: string): ReactElement {
  return <SourceEditor path={path} line={null} onShowFix={vi.fn()} />;
}

beforeEach(() => {
  for (const path of [A, B]) {
    disposeModel(sourceTabId(path));
    forgetDocument(sourceTabId(path));
  }
});

describe("SourceEditor", () => {
  describe("decoding a tab again", () => {
    /** Opens A, then has it decoded again under another project file, which reads the file at CHANGED. */
    async function decodeAgain(edited: boolean): Promise<void> {
      const decode = vi
        .fn()
        .mockResolvedValueOnce(decoded(READ))
        .mockResolvedValueOnce(decoded(CHANGED, "IBM930"));
      install(decode);
      render(providers(editor(A), { dbPath: "C:/data/p.db" }));
      await waitFor(() => expect(existingModel(sourceTabId(A))).not.toBeNull());
      if (edited) {
        const model = existingModel(sourceTabId(A)) as unknown as { edit(text: string): void };
        act(() => model.edit(`${PROGRAM}\n000600* EDITED`));
      }

      act(() =>
        dispatchProject({
          type: "SET_INVENTORY",
          result: { status: "ready", items: [] },
          dbPath: "C:/data/q.db",
        }),
      );

      await waitFor(() => expect(openDocument(sourceTabId(A))?.codepage).toBe("IBM930"));
    }

    it("keeps the stamp of the earlier decode while the tab holds a draft", async () => {
      await decodeAgain(true);
      expect(openDocument(sourceTabId(A))?.stamp).toEqual(READ);
    });

    it("takes the stamp of the new decode while the tab holds no draft", async () => {
      await decodeAgain(false);
      expect(openDocument(sourceTabId(A))?.stamp).toEqual(CHANGED);
    });
  });

  describe("the pane beside the code across a tab switch", () => {
    beforeEach(() => {
      // The pane is drawn where the body is wide enough beside the code, which jsdom never measures.
      vi.stubGlobal(
        "ResizeObserver",
        class {
          constructor(private readonly callback: () => void) {}
          observe(): void {
            this.callback();
          }
          disconnect(): void {}
          unobserve(): void {}
        },
      );
      vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(2000);
    });

    afterEach(() => {
      vi.unstubAllGlobals();
      vi.restoreAllMocks();
    });

    /** The rows of the list of paragraphs the Tab key enters at. */
    const outlineStops = (): string[] =>
      screen
        .getAllByRole("treeitem")
        .filter((row) => row.closest('[data-testid="outline-tree"]') !== null && row.tabIndex === 0)
        .map((row) => row.getAttribute("data-testid") ?? "");

    it("moves the list's Tab stop to a name pressed in the code, also when its paragraph is already shown", async () => {
      const text = [
        "000100 IDENTIFICATION DIVISION.",
        "000200 PROGRAM-ID. A.",
        "000300 PROCEDURE DIVISION.",
        "000400 MAIN-PROC.",
        "000500     PERFORM SUB-PROC.",
        "000600     GOBACK.",
        "000700 SUB-PROC.",
        "000800     EXIT.",
      ].join("\n");
      install(vi.fn(async () => ({ ...decoded(READ), text })));
      render(providers(editor(A)));
      await screen.findByTestId("outline-SUB-PROC");
      /**
       * Alt+F12 with the caret on SUB-PROC in the PERFORM. The pane's peek is an editor too, and only
       * the code's editor has the action, so it is run on every editor there is.
       */
      const press = (): void =>
        act(() => {
          for (const code of liveEditors()) {
            code.setPosition({ lineNumber: 5, column: 21 });
            code.runAction("cobolInsight.showParagraph");
          }
        });

      press();
      await waitFor(() => expect(outlineStops()).toEqual(["outline-SUB-PROC"]));

      // The reader moves the stop to the top of the list, then presses the same name again.
      fireEvent.keyDown(screen.getByTestId("outline-SUB-PROC"), { key: "Home" });
      expect(outlineStops()).not.toEqual(["outline-SUB-PROC"]);
      press();

      await waitFor(() => expect(outlineStops()).toEqual(["outline-SUB-PROC"]));
    });

    it("shows nothing of the previous tab's outline while the next tab is decoded", async () => {
      // B's decode never answers within the case, so the switch is caught before B has any text.
      install(
        vi.fn((request: { path: string }) =>
          request.path === A ? Promise.resolve(decoded(READ)) : new Promise(() => undefined),
        ),
      );
      const view = render(providers(editor(A)));
      expect(await screen.findByTestId("outline-MAIN-PROC")).toBeInTheDocument();

      view.rerender(providers(editor(B)));

      expect(screen.queryByTestId("outline-MAIN-PROC")).toBeNull();
      expect(screen.queryByTestId("paragraph-pane")).toBeNull();
    });
  });
});

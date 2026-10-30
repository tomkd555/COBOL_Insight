import { createElement, type ReactElement, type ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import type { CobolInsightApi, DecodeResult, SaveResult, SourceStamp } from "../../../shared/ipc";
import { text } from "../i18n/text";
import { forgetDocument, openDocument, rememberDocument } from "../model/openDocuments";
import { disposeModel, modelFor } from "../vendor/monacoModels";
import { ProjectProvider, initialProjectState, useProject } from "./projectStore";
import { SettingsProvider } from "./settingsStore";
import { useSourceSave } from "./useSourceSave";
import {
  WorkbenchProvider,
  initialWorkbenchState,
  sourceTab,
  sourceTabId,
  useWorkbench,
  useWorkbenchDispatch,
} from "./workbenchStore";

const INPUT_DIR = "C:/assets";
const PATHS = ["cobol/A.cbl", "cobol/B.cbl", "cobol/C.cbl"];
const READ: SourceStamp = { mtimeMs: 1, byteSize: 10 };
const CHANGED: SourceStamp = { mtimeMs: 2, byteSize: 10 };

const WRITTEN: SaveResult = { written: true, path: "", reparseErrors: [], error: "", exitCode: 0 };

const DECODED: DecodeResult = {
  text: "as it stands on disk",
  codepage: "Shift_JIS",
  detected: true,
  lines: [],
  stamp: CHANGED,
  error: "",
};

interface SaveApi {
  stat: ReturnType<typeof vi.fn>;
  save: ReturnType<typeof vi.fn>;
  decode: ReturnType<typeof vi.fn>;
}

/** The members of the published API a save uses; `changed` names the paths changed on disk. */
function fakeApi(changed: readonly string[] = [], overrides: Partial<SaveApi> = {}): SaveApi {
  const api: SaveApi = {
    stat: vi.fn(async (request: { path: string }) => (changed.includes(request.path) ? CHANGED : READ)),
    save: vi.fn(async () => WRITTEN),
    decode: vi.fn(async () => DECODED),
    ...overrides,
  };
  Object.defineProperty(window, "cobolInsight", {
    value: api as unknown as CobolInsightApi,
    configurable: true,
  });
  return api;
}

const notify = vi.fn();

/** The hook under test over `count` open tabs that all hold an unsaved edit. */
function renderSave(count = 1) {
  const paths = PATHS.slice(0, count);
  for (const path of paths) {
    rememberDocument(sourceTabId(path), path, {
      text: "original",
      codepage: "Shift_JIS",
      detected: true,
      lines: [],
      stamp: READ,
      error: "",
    });
  }
  const workbench = {
    ...initialWorkbenchState,
    tabs: paths.map((path) => sourceTab(path)),
    activeTabId: sourceTabId(paths[0]),
    drafts: Object.fromEntries(paths.map((path) => [sourceTabId(path), `edited ${path}`])),
  };
  const wrapper = ({ children }: { children: ReactNode }): ReactElement =>
    createElement(
      SettingsProvider,
      null,
      createElement(
        ProjectProvider,
        {
          initial: { ...initialProjectState, inputDir: INPUT_DIR },
          children: createElement(WorkbenchProvider, { initial: workbench, children }),
        },
      ),
    );
  return renderHook(
    () => ({
      save: useSourceSave(notify),
      drafts: useWorkbench().drafts,
      dispatch: useWorkbenchDispatch(),
      project: useProject(),
    }),
    { wrapper },
  );
}

beforeEach(() => {
  notify.mockClear();
  for (const path of PATHS) {
    forgetDocument(sourceTabId(path));
  }
});

describe("useSourceSave", () => {
  describe("saving one tab", () => {
    it("raises the conflict and keeps the draft when the file changed since it was read", async () => {
      const api = fakeApi([PATHS[0]]);
      const { result } = renderSave();

      let saved = true;
      await act(async () => {
        saved = await result.current.save.save(sourceTabId(PATHS[0]));
      });

      expect(saved).toBe(false);
      expect(result.current.save.conflict).toEqual({
        tabId: sourceTabId(PATHS[0]),
        path: PATHS[0],
        draft: `edited ${PATHS[0]}`,
        diskText: null,
      });
      expect(result.current.drafts[sourceTabId(PATHS[0])]).toBe(`edited ${PATHS[0]}`);
      expect(api.save).not.toHaveBeenCalled();
    });

    it("keeps the draft and notifies when the write fails", async () => {
      fakeApi([], {
        save: vi.fn(async () => ({ ...WRITTEN, written: false, exitCode: 2, error: "disk full" })),
      });
      const { result } = renderSave();

      let saved = true;
      await act(async () => {
        saved = await result.current.save.save(sourceTabId(PATHS[0]));
      });

      expect(saved).toBe(false);
      expect(notify).toHaveBeenCalledWith(text.save.failed(PATHS[0], "disk full"), true);
      expect(result.current.drafts[sourceTabId(PATHS[0])]).toBe(`edited ${PATHS[0]}`);
      expect(result.current.project.structureStale).toEqual([]);
    });

    it("keeps the draft and notifies when the call is rejected", async () => {
      fakeApi([], {
        save: vi.fn(async () => {
          throw new Error("ipc closed");
        }),
      });
      const { result } = renderSave();

      await act(async () => {
        await result.current.save.save(sourceTabId(PATHS[0]));
      });

      expect(notify).toHaveBeenCalledWith(text.save.failed(PATHS[0], "ipc closed"), true);
      expect(result.current.drafts[sourceTabId(PATHS[0])]).toBe(`edited ${PATHS[0]}`);
    });

    it("clears the draft and marks the asset as written when the save succeeds", async () => {
      const api = fakeApi();
      const { result } = renderSave();

      let saved = false;
      await act(async () => {
        saved = await result.current.save.save(sourceTabId(PATHS[0]));
      });

      expect(saved).toBe(true);
      expect(api.save).toHaveBeenCalledWith(
        expect.objectContaining({ baseDir: INPUT_DIR, path: PATHS[0], editedText: `edited ${PATHS[0]}` }),
      );
      expect(result.current.drafts).toEqual({});
      // SOURCE_SAVED: the asset is stale until the scan that follows has read it.
      expect(result.current.project.structureStale).toEqual([PATHS[0]]);
      expect(result.current.project.staleWrites).toBe(1);
      expect(notify).not.toHaveBeenCalled();
    });
  });

  describe("checking the stamp the tab was read at", () => {
    it("raises the conflict when a re-decode under the draft read a file that changed on disk", async () => {
      const api = fakeApi([PATHS[0]]);
      const { result } = renderSave();
      const id = sourceTabId(PATHS[0]);
      // The tab is decoded again while it holds its draft, and the file now carries CHANGED.
      rememberDocument(id, PATHS[0], DECODED, true);

      await act(async () => {
        await result.current.save.save(id);
      });

      expect(result.current.save.conflict?.path).toBe(PATHS[0]);
      expect(api.save).not.toHaveBeenCalled();
    });

    it("sends the tab's stamp with the save, so main checks it once the save's turn comes", async () => {
      const api = fakeApi();
      const { result } = renderSave();

      await act(async () => {
        await result.current.save.save(sourceTabId(PATHS[0]));
      });

      expect(api.save).toHaveBeenCalledWith(expect.objectContaining({ expectedStamp: READ }));
    });

    it("raises the conflict and keeps the draft when main finds the file changed in the save's turn", async () => {
      fakeApi([], {
        save: vi.fn(async () => ({ ...WRITTEN, written: false, exitCode: 2, stale: true })),
      });
      const { result } = renderSave();

      let saved = true;
      await act(async () => {
        saved = await result.current.save.save(sourceTabId(PATHS[0]));
      });

      expect(saved).toBe(false);
      expect(result.current.save.conflict?.path).toBe(PATHS[0]);
      expect(result.current.drafts[sourceTabId(PATHS[0])]).toBe(`edited ${PATHS[0]}`);
      expect(result.current.project.structureStale).toEqual([]);
      expect(notify).not.toHaveBeenCalled();
    });

    it("skips the check once after a stat that failed after the write, then takes a fresh stamp", async () => {
      let stats = 0;
      const api = fakeApi([], {
        // The check before the first save, then the stat after its write, which fails; from there on
        // the file reports CHANGED, as a write leaves it.
        stat: vi.fn(async () => {
          stats += 1;
          if (stats === 2) {
            throw new Error("stat failed after the write");
          }
          return stats === 1 ? READ : CHANGED;
        }),
      });
      const { result } = renderSave();
      const id = sourceTabId(PATHS[0]);

      for (const draft of ["second edit", "third edit"]) {
        await act(async () => {
          await result.current.save.save(id);
        });
        act(() => {
          result.current.dispatch({ type: "SET_DRAFT", id, draft });
        });
      }
      await act(async () => {
        await result.current.save.save(id);
      });

      expect(result.current.save.conflict).toBeNull();
      expect(api.save).toHaveBeenCalledTimes(3);
      expect(api.save.mock.calls[1][0].expectedStamp).toBeUndefined();
      expect(api.save.mock.calls[2][0].expectedStamp).toEqual(CHANGED);
    });
  });

  describe("what the engine's answer means for the tab", () => {
    async function saveWithFailedCheck() {
      const api = fakeApi([], {
        save: vi.fn(async () => ({ ...WRITTEN, exitCode: 2, error: "構文解析を完了できませんでした。" })),
      });
      const { result } = renderSave();

      let saved = false;
      await act(async () => {
        saved = await result.current.save.save(sourceTabId(PATHS[0]));
      });
      return { api, result, saved };
    }

    it("clears the draft when the file was replaced and the check failed", async () => {
      const { api, result, saved } = await saveWithFailedCheck();

      expect(saved).toBe(true);
      expect(result.current.drafts).toEqual({});
      expect(api.stat).toHaveBeenCalledTimes(2);
    });

    it("marks the asset written when the file was replaced and the check failed", async () => {
      const { result } = await saveWithFailedCheck();

      expect(result.current.project.structureStale).toEqual([PATHS[0]]);
      expect(notify).toHaveBeenCalledWith(
        text.save.checkFailed(PATHS[0], "構文解析を完了できませんでした。"),
      );
    });

    it("clears the draft and says nothing when the disk already held the text", async () => {
      const api = fakeApi([], { save: vi.fn(async () => ({ ...WRITTEN, written: false, exitCode: 0 })) });
      const { result } = renderSave();

      let saved = false;
      await act(async () => {
        saved = await result.current.save.save(sourceTabId(PATHS[0]));
      });

      expect(saved).toBe(true);
      expect(result.current.drafts).toEqual({});
      expect(notify).not.toHaveBeenCalled();
      // The stamp is taken afresh, so the next save measures the file against it.
      expect(api.stat).toHaveBeenCalledTimes(2);
    });

    it("leaves no draft for a tab closed during its save, and still marks the asset written", async () => {
      let release: () => void = () => undefined;
      const gate = new Promise<void>((done) => {
        release = done;
      });
      fakeApi([], {
        save: vi.fn(async () => {
          await gate;
          return WRITTEN;
        }),
      });
      const { result } = renderSave();
      const id = sourceTabId(PATHS[0]);

      let saving: Promise<boolean> = Promise.resolve(false);
      await act(async () => {
        saving = result.current.save.save(id);
        await Promise.resolve();
      });
      // What the shell does on closing: the tab, its draft and its decode go.
      act(() => result.current.dispatch({ type: "CLOSE_TAB", id }));
      forgetDocument(id);
      await act(async () => {
        release();
        await saving;
      });

      expect(result.current.drafts[id]).toBeUndefined();
      expect(openDocument(id)).toBeNull();
      expect(result.current.project.structureStale).toEqual([PATHS[0]]);
    });

    it("leaves no draft when the editor's text returned during the save to the text being saved", async () => {
      let release: () => void = () => undefined;
      const gate = new Promise<void>((done) => {
        release = done;
      });
      fakeApi([], {
        save: vi.fn(async () => {
          await gate;
          return WRITTEN;
        }),
      });
      const { result } = renderSave();
      const id = sourceTabId(PATHS[0]);
      const model = modelFor(id, `edited ${PATHS[0]}`, "cobol");

      let saving: Promise<boolean> = Promise.resolve(false);
      await act(async () => {
        saving = result.current.save.save(id);
        await Promise.resolve();
      });
      // Typing, then undoing back: the store still holds the typed draft, the editor the saved text.
      act(() => result.current.dispatch({ type: "SET_DRAFT", id, draft: "typed during the save" }));
      model.setValue(`edited ${PATHS[0]}`);
      await act(async () => {
        release();
        await saving;
      });

      expect(result.current.drafts).toEqual({});
      disposeModel(id);
    });
  });

  describe("saving one tab twice", () => {
    it("writes a second save after the first, against the stamp the first one left, with no conflict", async () => {
      let release: () => void = () => undefined;
      const gate = new Promise<void>((done) => {
        release = done;
      });
      let writes = 0;
      const api = fakeApi([], {
        // Before any write the file carries READ; every write leaves it at CHANGED.
        stat: vi.fn(async () => (writes > 0 ? CHANGED : READ)),
        save: vi.fn(async () => {
          if (writes === 0) {
            await gate;
          }
          writes += 1;
          return WRITTEN;
        }),
      });
      const { result } = renderSave();
      const id = sourceTabId(PATHS[0]);

      let first: Promise<boolean> = Promise.resolve(false);
      await act(async () => {
        first = result.current.save.save(id);
        await Promise.resolve();
      });
      await vi.waitFor(() => expect(api.save).toHaveBeenCalledTimes(1));
      act(() => result.current.dispatch({ type: "SET_DRAFT", id, draft: "typed during the save" }));
      let second: Promise<boolean> = Promise.resolve(false);
      act(() => {
        second = result.current.save.save(id);
      });
      await act(async () => {
        release();
        await first;
        await second;
      });

      expect(result.current.save.conflict).toBeNull();
      expect(api.save).toHaveBeenCalledTimes(2);
      expect(api.save.mock.calls[1][0]).toMatchObject({
        editedText: "typed during the save",
        expectedStamp: CHANGED,
      });
      expect(result.current.drafts).toEqual({});
    });

    it("writes once for a second save asked for while the first writes the same text", async () => {
      let release: () => void = () => undefined;
      const gate = new Promise<void>((done) => {
        release = done;
      });
      const api = fakeApi([], {
        save: vi.fn(async () => {
          await gate;
          return WRITTEN;
        }),
      });
      const { result } = renderSave();
      const id = sourceTabId(PATHS[0]);

      let both: Promise<boolean[]> = Promise.resolve([]);
      await act(async () => {
        both = Promise.all([result.current.save.save(id), result.current.save.save(id)]);
        await Promise.resolve();
      });
      await act(async () => {
        release();
        await both;
      });

      expect(await both).toEqual([true, true]);
      expect(api.save).toHaveBeenCalledTimes(1);
      expect(notify).not.toHaveBeenCalled();
    });
  });

  describe("saving while the text changes and the stamp fails", () => {
    it("leaves the tab unsaved when the text typed during the save returns to what it held before", async () => {
      let release: () => void = () => undefined;
      const gate = new Promise<void>((done) => {
        release = done;
      });
      fakeApi([], {
        save: vi.fn(async () => {
          await gate;
          return WRITTEN;
        }),
      });
      const { result } = renderSave();
      const id = sourceTabId(PATHS[0]);

      let saving: Promise<boolean> = Promise.resolve(false);
      await act(async () => {
        saving = result.current.save.save(id);
        await Promise.resolve();
      });
      // An undo back to the text as read: the editor counts it as unchanged and drops the draft.
      act(() => result.current.dispatch({ type: "SET_DRAFT", id, draft: null }));
      await act(async () => {
        release();
        await saving;
      });

      // The disk holds the saved text and the editor the text read before it.
      expect(result.current.drafts[id]).toBe("original");
    });

    it("keeps a draft typed while the save is in flight and still marks the asset as written", async () => {
      let release: () => void = () => undefined;
      const gate = new Promise<void>((done) => {
        release = done;
      });
      const api = fakeApi([], {
        save: vi.fn(async () => {
          await gate;
          return WRITTEN;
        }),
      });
      const { result } = renderSave();
      const id = sourceTabId(PATHS[0]);

      let saving: Promise<boolean> = Promise.resolve(false);
      await act(async () => {
        saving = result.current.save.save(id);
        await Promise.resolve();
      });
      await vi.waitFor(() => expect(api.save).toHaveBeenCalledTimes(1));
      act(() => {
        result.current.dispatch({ type: "SET_DRAFT", id, draft: "typed during the save" });
      });
      await act(async () => {
        release();
        await saving;
      });

      expect(result.current.drafts[id]).toBe("typed during the save");
      expect(result.current.project.structureStale).toEqual([PATHS[0]]);
    });

    it("clears the draft and dispatches SOURCE_SAVED when the stat after the write rejects", async () => {
      let stats = 0;
      fakeApi([], {
        stat: vi.fn(async () => {
          stats += 1;
          if (stats > 1) {
            throw new Error("stat failed after the write");
          }
          return READ;
        }),
      });
      const { result } = renderSave();

      let saved = false;
      await act(async () => {
        saved = await result.current.save.save(sourceTabId(PATHS[0]));
      });

      expect(saved).toBe(true);
      expect(result.current.drafts).toEqual({});
      expect(result.current.project.structureStale).toEqual([PATHS[0]]);
      expect(result.current.project.staleWrites).toBe(1);
    });
  });

  describe("answering a conflict", () => {
    /** Saves the first tab of a file that changed on disk, leaving the conflict raised. */
    async function raiseConflict(api: SaveApi) {
      const { result } = renderSave();
      await act(async () => {
        await result.current.save.save(sourceTabId(PATHS[0]));
      });
      expect(result.current.save.conflict).not.toBeNull();
      expect(api.save).not.toHaveBeenCalled();
      return result;
    }

    it("writes the draft over the changed file without asking the stamp again", async () => {
      const api = fakeApi([PATHS[0]]);
      const result = await raiseConflict(api);
      api.stat.mockClear();

      await act(async () => {
        result.current.save.overwrite();
      });

      expect(api.save).toHaveBeenCalledTimes(1);
      expect(api.save).toHaveBeenCalledWith(
        expect.objectContaining({ path: PATHS[0], editedText: `edited ${PATHS[0]}` }),
      );
      // Main is asked for no check either.
      expect(api.save.mock.calls[0][0].expectedStamp).toBeUndefined();
      // The only stat is the one that records the stamp of the file just written.
      expect(api.stat).toHaveBeenCalledTimes(1);
      expect(result.current.save.conflict).toBeNull();
      expect(result.current.drafts).toEqual({});
      expect(result.current.project.structureStale).toEqual([PATHS[0]]);
    });

    it("says there is nothing to save when the draft is gone by the time of the overwrite", async () => {
      const api = fakeApi([PATHS[0]]);
      const result = await raiseConflict(api);
      act(() => result.current.dispatch({ type: "SET_DRAFT", id: sourceTabId(PATHS[0]), draft: null }));

      await act(async () => {
        result.current.save.overwrite();
      });

      expect(api.save).not.toHaveBeenCalled();
      expect(notify).toHaveBeenCalledWith(text.save.nothingToSave);
    });

    it("holds the file as it stands on disk once the difference was asked for", async () => {
      const api = fakeApi([PATHS[0]]);
      const result = await raiseConflict(api);

      await act(async () => {
        result.current.save.showConflictDiff();
      });

      expect(result.current.save.conflict?.diskText).toBe(DECODED.text);
      expect(notify).not.toHaveBeenCalled();
    });

    it("sets no diff when the decode of the changed file failed with an empty text", async () => {
      const api = fakeApi([PATHS[0]], {
        decode: vi.fn(async () => ({ ...DECODED, text: "", error: "cannot decode" })),
      });
      const result = await raiseConflict(api);

      await act(async () => {
        result.current.save.showConflictDiff();
      });

      expect(api.decode).toHaveBeenCalledTimes(1);
      expect(result.current.save.conflict?.diskText).toBeNull();
      expect(notify).toHaveBeenCalledWith("cannot decode", true);
    });
  });

  describe("throwing the edits away", () => {
    it("reports a decode that was rejected and leaves the tab as it was", async () => {
      fakeApi([], {
        decode: vi.fn(async () => {
          throw new Error("ipc closed");
        }),
      });
      const { result } = renderSave();
      const id = sourceTabId(PATHS[0]);

      await act(async () => {
        await result.current.save.reload(id);
      });

      expect(notify).toHaveBeenCalledWith("ipc closed", true);
      expect(result.current.drafts[id]).toBe(`edited ${PATHS[0]}`);
    });
  });

  describe("saving every tab", () => {
    it("stops at the first tab that conflicts and says how many are still unsaved", async () => {
      const api = fakeApi([PATHS[1]]);
      const { result } = renderSave(3);

      await act(async () => {
        await result.current.save.saveAll();
      });

      expect(api.save).toHaveBeenCalledTimes(1);
      expect(result.current.save.conflict?.path).toBe(PATHS[1]);
      expect(notify).toHaveBeenCalledWith(text.save.unsavedRemain(2), true);
      expect(Object.keys(result.current.drafts)).toEqual([sourceTabId(PATHS[1]), sourceTabId(PATHS[2])]);
      expect(result.current.project.structureStale).toEqual([PATHS[0]]);
    });

    it("reports saving while it runs and stops reporting it once it has finished", async () => {
      let release: () => void = () => undefined;
      const gate = new Promise<void>((done) => {
        release = done;
      });
      fakeApi([], {
        save: vi.fn(async () => {
          await gate;
          return WRITTEN;
        }),
      });
      const { result } = renderSave(2);
      expect(result.current.save.savingAll).toBe(false);

      let finished: Promise<void> = Promise.resolve();
      act(() => {
        finished = result.current.save.saveAll();
      });
      expect(result.current.save.savingAll).toBe(true);

      await act(async () => {
        release();
        await finished;
      });
      expect(result.current.save.savingAll).toBe(false);
      expect(result.current.drafts).toEqual({});
    });

    it("writes nothing more for a second saveAll started while one runs", async () => {
      let release: () => void = () => undefined;
      const gate = new Promise<void>((done) => {
        release = done;
      });
      const api = fakeApi([], {
        save: vi.fn(async () => {
          await gate;
          return WRITTEN;
        }),
      });
      const { result } = renderSave(2);

      let first: Promise<void> = Promise.resolve();
      let second: Promise<void> = Promise.resolve();
      await act(async () => {
        first = result.current.save.saveAll();
        second = result.current.save.saveAll();
        await Promise.resolve();
      });
      await vi.waitFor(() => expect(api.save).toHaveBeenCalledTimes(1));

      await act(async () => {
        release();
        await first;
        await second;
      });

      expect(api.save).toHaveBeenCalledTimes(2);
      expect(result.current.drafts).toEqual({});
    });

    it("clears the saving flag once it stopped early", async () => {
      fakeApi([PATHS[0]]);
      const { result } = renderSave(2);

      await act(async () => {
        await result.current.save.saveAll();
      });

      expect(result.current.save.savingAll).toBe(false);
      expect(notify).toHaveBeenCalledWith(text.save.unsavedRemain(2), true);
    });

    it("writes nothing and clears the saving flag where the file's stamp cannot be read", async () => {
      const api = fakeApi([], {
        stat: vi.fn(async () => {
          throw new Error("stat failed");
        }),
      });
      const { result } = renderSave(2);

      await act(async () => {
        await result.current.save.saveAll();
      });

      expect(api.save).not.toHaveBeenCalled();
      expect(result.current.save.savingAll).toBe(false);
      expect(notify).toHaveBeenCalledWith(text.save.failed(PATHS[0], "stat failed"), true);
      expect(notify).toHaveBeenCalledWith(text.save.unsavedRemain(2), true);
      expect(result.current.drafts[sourceTabId(PATHS[0])]).toBe(`edited ${PATHS[0]}`);
    });

    it("passes over a tab closed while an earlier one was being written", async () => {
      let release: () => void = () => undefined;
      const gate = new Promise<void>((done) => {
        release = done;
      });
      const api = fakeApi([], {
        save: vi.fn(async () => {
          await gate;
          return WRITTEN;
        }),
      });
      const { result } = renderSave(3);

      let finished: Promise<void> = Promise.resolve();
      await act(async () => {
        finished = result.current.save.saveAll();
        await Promise.resolve();
      });
      await vi.waitFor(() => expect(api.save).toHaveBeenCalledTimes(1));
      act(() => result.current.dispatch({ type: "CLOSE_TAB", id: sourceTabId(PATHS[1]) }));
      await act(async () => {
        release();
        await finished;
      });

      expect(api.save.mock.calls.map((call) => (call[0] as { path: string }).path)).toEqual([
        PATHS[0],
        PATHS[2],
      ]);
      expect(notify).not.toHaveBeenCalled();
    });

    it("carries on past a tab closed while it waited for an earlier save of that tab", async () => {
      let release: () => void = () => undefined;
      const gate = new Promise<void>((done) => {
        release = done;
      });
      const api = fakeApi([], {
        save: vi.fn(async (request: { path: string }) => {
          if (request.path === PATHS[0]) {
            await gate;
          }
          return WRITTEN;
        }),
      });
      const { result } = renderSave(2);
      const first = sourceTabId(PATHS[0]);

      // A save of the first tab is under way when the run starts, so the run's save of it waits.
      let earlier: Promise<boolean> = Promise.resolve(false);
      await act(async () => {
        earlier = result.current.save.save(first);
        await Promise.resolve();
      });
      act(() => result.current.dispatch({ type: "SET_DRAFT", id: first, draft: "typed again" }));
      let finished: Promise<void> = Promise.resolve();
      await act(async () => {
        finished = result.current.save.saveAll();
        await Promise.resolve();
      });
      act(() => result.current.dispatch({ type: "CLOSE_TAB", id: first }));
      forgetDocument(first);
      await act(async () => {
        release();
        await earlier;
        await finished;
      });

      expect(api.save.mock.calls.map((call) => (call[0] as { path: string }).path)).toEqual([
        PATHS[0],
        PATHS[1],
      ]);
      expect(notify).not.toHaveBeenCalled();
    });

    it("counts only the tabs still unsaved when it stops", async () => {
      let release: () => void = () => undefined;
      const gate = new Promise<void>((done) => {
        release = done;
      });
      // The second tab conflicts; the third is written by a save of its own while the run writes the first.
      const api = fakeApi([PATHS[1]], {
        save: vi.fn(async (request: { path: string }) => {
          if (request.path === PATHS[0]) {
            await gate;
          }
          return WRITTEN;
        }),
      });
      const { result } = renderSave(3);

      let finished: Promise<void> = Promise.resolve();
      await act(async () => {
        finished = result.current.save.saveAll();
        await Promise.resolve();
      });
      await vi.waitFor(() => expect(api.save).toHaveBeenCalledTimes(1));
      await act(async () => {
        await result.current.save.save(sourceTabId(PATHS[2]));
      });
      await act(async () => {
        release();
        await finished;
      });

      expect(result.current.save.conflict?.path).toBe(PATHS[1]);
      expect(notify).toHaveBeenCalledWith(text.save.unsavedRemain(1), true);
    });
  });
});

import { describe, expect, it, vi } from "vitest";
import { initialProjectState, type ProjectState } from "./projectStore";
import {
  initialWorkbenchState,
  settingsTab,
  sourceTab,
  transpileTab,
  type WorkbenchState,
} from "./workbenchStore";
import { availableCommands, buildCommands, filterCommands, type CommandContext } from "./commands";
import { KEYBINDINGS, commandForChord, isPaletteChord, isTextEntry } from "./keybindings";

function context(overrides: Partial<CommandContext> = {}): CommandContext {
  return {
    project: initialProjectState,
    workbench: initialWorkbenchState,
    workbenchDispatch: vi.fn(),
    selectFolder: vi.fn(),
    runAnalysis: vi.fn(),
    cancelAnalysis: vi.fn(),
    requestCloseTab: vi.fn(),
    requestDiscardTab: vi.fn(),
    rulesActions: {
      setRulesEnabled: vi.fn(),
      setRuleSeverity: vi.fn(),
      saveCustomRules: vi.fn(),
      validateCustomRules: vi.fn(),
    },
    saveActiveTab: vi.fn(),
    saveAllTabs: vi.fn(),
    hasDirty: false,
    ...overrides,
  };
}

const withFolder: ProjectState = { ...initialProjectState, inputDir: "C:/assets" };
const running: ProjectState = { ...withFolder, mode: "running" };
/** A scanned folder holding one COBOL program and one copybook, which is not translated. */
const scanned: ProjectState = {
  ...withFolder,
  inventory: {
    status: "ready",
    items: [
      { id: 1, path: "a.cbl", name: "a.cbl", type: "PROGRAM", codepage: null, byteSize: 1, findingCount: 0 },
      { id: 2, path: "b.cpy", name: "b.cpy", type: "COPYBOOK", codepage: null, byteSize: 1, findingCount: 0 },
    ],
  },
};
const withTab: WorkbenchState = {
  ...initialWorkbenchState,
  tabs: [sourceTab("a.cbl")],
  activeTabId: sourceTab("a.cbl").id,
};

describe("the command list", () => {
  const commands = buildCommands(context());

  it("gives every command a unique id", () => {
    const ids = commands.map((command) => command.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("gives every command a title and a category", () => {
    for (const command of commands) {
      expect(command.title, command.id).not.toBe("");
      expect(command.category, command.id).not.toBe("");
    }
  });
});

describe("every keybinding resolves", () => {
  it("names a command that exists", () => {
    const ids = new Set(buildCommands(context()).map((command) => command.id));
    for (const binding of KEYBINDINGS) {
      expect(ids, binding.chord).toContain(binding.command);
    }
  });

  it("binds each chord only once", () => {
    const chords = KEYBINDINGS.map((binding) => `${binding.key}:${binding.shift}`);
    expect(new Set(chords).size).toBe(chords.length);
  });
});

describe("when", () => {
  it("offers running only with a folder chosen and nothing already running", () => {
    const offered = (project: ProjectState): boolean =>
      availableCommands(buildCommands(context({ project }))).some(
        (command) => command.id === "run.analyze",
      );
    expect(offered(initialProjectState)).toBe(false);
    expect(offered(withFolder)).toBe(true);
    expect(offered(running)).toBe(false);
  });

  it("offers cancelling only while a run is in progress", () => {
    const offered = (project: ProjectState): boolean =>
      availableCommands(buildCommands(context({ project }))).some(
        (command) => command.id === "run.cancel",
      );
    expect(offered(withFolder)).toBe(false);
    expect(offered(running)).toBe(true);
  });

  it("offers the translation only for a COBOL source tab", () => {
    const offered = (workbench: WorkbenchState): boolean =>
      availableCommands(buildCommands(context({ workbench, project: scanned }))).some(
        (command) => command.id === "view.showTranspile",
      );
    expect(offered(initialWorkbenchState)).toBe(false);
    expect(offered(withTab)).toBe(true);
    // A copybook has no translation, so the command does not apply on its tab.
    const copybook = sourceTab("b.cpy");
    expect(offered({ ...initialWorkbenchState, tabs: [copybook], activeTabId: copybook.id })).toBe(
      false,
    );
    const settings = settingsTab("設定");
    expect(offered({ ...initialWorkbenchState, tabs: [settings], activeTabId: settings.id })).toBe(
      false,
    );
  });

  it("offers the discard only on a tab that holds unsaved edits", () => {
    const offered = (workbench: WorkbenchState): boolean =>
      availableCommands(buildCommands(context({ workbench }))).some(
        (command) => command.id === "editor.discard",
      );
    expect(offered(withTab)).toBe(false);
    expect(offered({ ...withTab, drafts: { [sourceTab("a.cbl").id]: "EDITED" } })).toBe(true);
  });

  it("offers the save only on a source tab that holds unsaved edits", () => {
    const offered = (workbench: WorkbenchState): boolean =>
      availableCommands(buildCommands(context({ workbench, hasDirty: true }))).some(
        (command) => command.id === "editor.save",
      );
    const dirty = { ...withTab, drafts: { [sourceTab("a.cbl").id]: "EDITED" } };
    expect(offered(dirty)).toBe(true);
    // Clean source tab, and another kind of tab while a source tab elsewhere is unsaved.
    expect(offered(withTab)).toBe(false);
    const settings = settingsTab("設定");
    expect(
      offered({ ...dirty, tabs: [...dirty.tabs, settings], activeTabId: settings.id }),
    ).toBe(false);
  });

  it("offers the tab commands only when a tab is open", () => {
    const withoutTabs = availableCommands(buildCommands(context())).map((command) => command.id);
    expect(withoutTabs).not.toContain("editor.closeTab");
    const withTabs = availableCommands(buildCommands(context({ workbench: withTab }))).map(
      (command) => command.id,
    );
    expect(withTabs).toContain("editor.closeTab");
    expect(withTabs).toContain("editor.nextTab");
  });
});

describe("run", () => {
  it("routes closing through the guard rather than closing outright", () => {
    const requestCloseTab = vi.fn();
    const commands = buildCommands(context({ workbench: withTab, requestCloseTab }));
    commands.find((command) => command.id === "editor.closeTab")?.run();
    expect(requestCloseTab).toHaveBeenCalledWith(withTab.activeTabId);
  });

  it("opens the translation of the asset the active source tab shows", () => {
    const workbenchDispatch = vi.fn();
    const commands = buildCommands(
      context({ workbench: withTab, workbenchDispatch, project: scanned }),
    );
    commands.find((command) => command.id === "view.showTranspile")?.run();
    expect(workbenchDispatch).toHaveBeenCalledWith({
      type: "OPEN_TAB",
      tab: transpileTab("a.cbl"),
    });
  });

  it("dispatches the layout toggles", () => {
    const workbenchDispatch = vi.fn();
    const commands = buildCommands(context({ workbenchDispatch }));
    commands.find((command) => command.id === "view.togglePanel")?.run();
    expect(workbenchDispatch).toHaveBeenCalledWith({ type: "TOGGLE_PANEL" });
  });
});

describe("filterCommands", () => {
  const commands = buildCommands(context({ project: withFolder }));

  it("keeps everything for an empty query", () => {
    expect(filterCommands(commands, "   ")).toHaveLength(commands.length);
  });

  it("matches the title and the category, ignoring case", () => {
    expect(filterCommands(commands, "パネル").map((command) => command.id)).toEqual([
      "view.togglePanel",
    ]);
    expect(filterCommands(commands, "表示").length).toBeGreaterThan(1);
  });

  it("returns nothing when nothing matches", () => {
    expect(filterCommands(commands, "zzzz")).toEqual([]);
  });
});

describe("chords", () => {
  const chord = (key: string, shift = false, target: EventTarget | null = null) => ({
    key,
    ctrlKey: true,
    metaKey: false,
    altKey: false,
    shiftKey: shift,
    target,
  });

  it("maps the bound chords onto their commands", () => {
    expect(commandForChord(chord("b"))).toBe("view.toggleSideBar");
    expect(commandForChord(chord("J"))).toBe("view.togglePanel");
    expect(commandForChord(chord("PageDown"))).toBe("editor.nextTab");
    expect(commandForChord(chord("e", true))).toBe("view.showExplorer");
  });

  it("ignores an unbound key and a chord with the wrong modifiers", () => {
    expect(commandForChord(chord("q"))).toBeNull();
    expect(commandForChord({ ...chord("b"), ctrlKey: false })).toBeNull();
    expect(commandForChord({ ...chord("b"), altKey: true })).toBeNull();
  });

  it("recognises the palette chord", () => {
    expect(isPaletteChord(chord("p", true))).toBe(true);
    expect(isPaletteChord(chord("p", false))).toBe(false);
  });

  it("does not steal a chord from a text field", () => {
    const input = document.createElement("input");
    expect(commandForChord(chord("b", false, input))).toBeNull();
    expect(isPaletteChord(chord("p", true, input))).toBe(false);
  });

  it("still fires inside the code editor's hidden textarea", () => {
    // Monaco takes every keystroke through a hidden textarea, so excluding all multi-line inputs
    // would disable these chords exactly where they are most needed.
    const editor = document.createElement("div");
    editor.className = "monaco-editor";
    const textarea = document.createElement("textarea");
    editor.appendChild(textarea);
    document.body.appendChild(editor);
    try {
      expect(isTextEntry(textarea)).toBe(false);
      expect(commandForChord(chord("b", false, textarea))).toBe("view.toggleSideBar");
    } finally {
      editor.remove();
    }
  });
});

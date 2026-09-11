/**
 * Every action the shell can perform, in one array.
 *
 * The command palette, the keyboard chords and any menu all read this one list, so an action cannot
 * exist on one route and be missing from another. A command holds an id, a title, a category, a
 * `when` predicate that decides whether it currently applies, and the function that runs it.
 */

import type { Dispatch } from "react";
import { text } from "../i18n/text";
import { artifactItems, type ProjectState } from "./projectStore";
import { assetTypeOf } from "../model/assetTree";
import {
  graphTab,
  isTabDirty,
  reportTab,
  settingsTab,
  transpileTab,
  type WorkbenchAction,
  type WorkbenchState,
} from "./workbenchStore";

/** Command ids. The keybinding table maps chords onto these. */
export type CommandId =
  | "file.selectFolder"
  | "run.analyze"
  | "run.analyzeAll"
  | "run.cancel"
  | "view.toggleSideBar"
  | "view.togglePanel"
  | "view.showExplorer"
  | "view.showRules"
  | "view.showProblems"
  | "view.showOutput"
  | "view.showGraph"
  | "view.showReport"
  | "view.showSettings"
  | "view.showTranspile"
  | "editor.closeTab"
  | "editor.nextTab"
  | "editor.previousTab"
  | "editor.save"
  | "editor.saveAll";

export interface Command {
  readonly id: CommandId;
  readonly title: string;
  readonly category: string;
  /** Whether the command applies right now. A command that does not is hidden and does not fire. */
  readonly when: () => boolean;
  readonly run: () => void;
}

/** What the command implementations need from the rest of the application. */
export interface CommandContext {
  readonly project: ProjectState;
  readonly workbench: WorkbenchState;
  readonly workbenchDispatch: Dispatch<WorkbenchAction>;
  /** Opens the folder picker and, when a folder is chosen, scans it. */
  readonly selectFolder: () => void;
  /** Analyses what the explorer has selected, and says so when nothing is. */
  readonly runAnalysis: () => void;
  /** Analyses the whole folder, scan and lint both. */
  readonly runAnalysisAll: () => void;
  readonly cancelAnalysis: () => void;
  /** Closes a tab, asking first when it holds unsaved edits. */
  readonly requestCloseTab: (id: string) => void;
  /** Writes the selected tab's edits back over the original. */
  readonly saveActiveTab: () => void;
  /** Writes every unsaved tab back, one after another. */
  readonly saveAllTabs: () => void;
  /** Whether anything is unsaved, which is what makes the two save commands apply. */
  readonly hasDirty: boolean;
}

/** The asset the active tab shows the source of, or null when the active tab shows none. */
function activeSourcePath(workbench: WorkbenchState): string | null {
  const active = workbench.tabs.find((tab) => tab.id === workbench.activeTabId);
  return active?.kind === "source" ? active.path : null;
}

/**
 * The asset the active source tab shows, when the scan filed that asset as COBOL. Exported so the
 * activity bar can grey out the transpile icon with the same rule the command itself applies.
 */
export function activeCobolPath(project: ProjectState, workbench: WorkbenchState): string | null {
  const path = activeSourcePath(workbench);
  const item = artifactItems(project.inventory).find((each) => each.path === path);
  return item !== undefined && assetTypeOf(item.type) === "cobol" ? item.path : null;
}

/**
 * Whether the active tab is a source tab holding unsaved edits. Only such a tab can be written, so
 * "unsaved work exists somewhere" is not enough: on any other tab the save would find nothing to
 * write and say nothing about it.
 */
function activeTabDirty(workbench: WorkbenchState): boolean {
  const active = workbench.tabs.find((tab) => tab.id === workbench.activeTabId);
  return active !== undefined && active.kind === "source" && isTabDirty(workbench, active.id);
}

/** Builds the command list for the current context. */
export function buildCommands(context: CommandContext): Command[] {
  const { project, workbench, workbenchDispatch } = context;
  const running = (): boolean => project.mode === "running";
  const hasFolder = (): boolean => project.inputDir !== null;
  const hasTabs = (): boolean => workbench.tabs.length > 0;

  return [
    {
      id: "file.selectFolder",
      title: text.command.selectFolder,
      category: text.command.categoryFile,
      when: () => !running(),
      run: context.selectFolder,
    },
    {
      id: "run.analyze",
      title: text.command.run,
      category: text.command.categoryRun,
      when: () => hasFolder() && !running(),
      run: context.runAnalysis,
    },
    {
      id: "run.analyzeAll",
      title: text.command.runAll,
      category: text.command.categoryRun,
      when: () => hasFolder() && !running(),
      run: context.runAnalysisAll,
    },
    {
      id: "run.cancel",
      title: text.command.cancel,
      category: text.command.categoryRun,
      when: running,
      run: context.cancelAnalysis,
    },
    {
      id: "view.toggleSideBar",
      title: text.command.toggleSideBar,
      category: text.command.categoryView,
      when: () => true,
      run: () => workbenchDispatch({ type: "TOGGLE_SIDE" }),
    },
    {
      id: "view.togglePanel",
      title: text.command.togglePanel,
      category: text.command.categoryView,
      when: () => true,
      run: () => workbenchDispatch({ type: "TOGGLE_PANEL" }),
    },
    {
      id: "view.showExplorer",
      title: text.command.showExplorer,
      category: text.command.categoryView,
      when: () => true,
      run: () => workbenchDispatch({ type: "SHOW_SIDE", view: "explorer" }),
    },
    {
      id: "view.showRules",
      title: text.command.showRules,
      category: text.command.categoryView,
      when: () => true,
      run: () => workbenchDispatch({ type: "SHOW_SIDE", view: "rules" }),
    },
    {
      id: "view.showProblems",
      title: text.command.showProblems,
      category: text.command.categoryView,
      when: () => true,
      run: () => workbenchDispatch({ type: "SHOW_PANEL", view: "problems" }),
    },
    {
      id: "view.showOutput",
      title: text.command.showOutput,
      category: text.command.categoryView,
      when: () => true,
      run: () => workbenchDispatch({ type: "SHOW_PANEL", view: "output" }),
    },
    {
      id: "view.showGraph",
      title: text.command.showGraph,
      category: text.command.categoryView,
      when: () => true,
      run: () => workbenchDispatch({ type: "OPEN_TAB", tab: graphTab(text.graph.title) }),
    },
    {
      id: "view.showReport",
      title: text.command.showReport,
      category: text.command.categoryView,
      when: () => true,
      run: () => workbenchDispatch({ type: "OPEN_TAB", tab: reportTab(text.report.title) }),
    },
    {
      id: "view.showSettings",
      title: text.command.showSettings,
      category: text.command.categoryView,
      when: () => true,
      run: () => workbenchDispatch({ type: "OPEN_TAB", tab: settingsTab(text.settings.title) }),
    },
    {
      id: "view.showTranspile",
      title: text.command.showTranspile,
      category: text.command.categoryView,
      // Only COBOL is translated, so the command does not apply to a copybook, JCL or BMS tab.
      when: () => activeCobolPath(project, workbench) !== null,
      run: () => {
        const path = activeCobolPath(project, workbench);
        if (path !== null) {
          workbenchDispatch({ type: "OPEN_TAB", tab: transpileTab(path) });
        }
      },
    },
    {
      id: "editor.closeTab",
      title: text.command.closeTab,
      category: text.command.categoryFile,
      when: () => workbench.activeTabId !== null,
      run: () => {
        if (workbench.activeTabId !== null) {
          context.requestCloseTab(workbench.activeTabId);
        }
      },
    },
    {
      id: "editor.save",
      title: text.command.save,
      category: text.command.categoryFile,
      // A tab with nothing unsaved has nothing to write, so the command does not apply.
      when: () => activeTabDirty(workbench),
      run: context.saveActiveTab,
    },
    {
      id: "editor.saveAll",
      title: text.command.saveAll,
      category: text.command.categoryFile,
      when: () => context.hasDirty,
      run: context.saveAllTabs,
    },
    {
      id: "editor.nextTab",
      title: text.command.nextTab,
      category: text.command.categoryView,
      when: hasTabs,
      run: () => workbenchDispatch({ type: "STEP_TAB", step: 1 }),
    },
    {
      id: "editor.previousTab",
      title: text.command.previousTab,
      category: text.command.categoryView,
      when: hasTabs,
      run: () => workbenchDispatch({ type: "STEP_TAB", step: -1 }),
    },
  ];
}

/** The commands that apply right now, in the order they were declared. */
export function availableCommands(commands: readonly Command[]): Command[] {
  return commands.filter((command) => command.when());
}

/**
 * Filters commands by what was typed, matching case-insensitively against the title and the
 * category. An empty query keeps everything.
 */
export function filterCommands(commands: readonly Command[], query: string): Command[] {
  const needle = query.trim().toLowerCase();
  if (needle === "") {
    return [...commands];
  }
  return commands.filter((command) =>
    `${command.category} ${command.title}`.toLowerCase().includes(needle),
  );
}

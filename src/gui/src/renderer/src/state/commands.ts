/**
 * Every action the shell can perform, in one array.
 *
 * The command palette, the keyboard chords and any menu all read this one list, so an action cannot
 * exist on one route and be missing from another. A command holds an id, a title, a category, a
 * `when` predicate that decides whether it currently applies, and the function that runs it.
 */

import type { Dispatch } from "react";
import { text } from "../text";
import type { ProjectState } from "./projectStore";
import {
  CUSTOM_RULES_TAB_ID,
  settingsTab,
  transpileTab,
  type WorkbenchAction,
  type WorkbenchState,
} from "./workbenchStore";
import type { RulesActions } from "./useRules";

/** Command ids. The keybinding table maps chords onto these. */
export type CommandId =
  | "file.selectFolder"
  | "run.analyze"
  | "run.cancel"
  | "view.toggleSideBar"
  | "view.togglePanel"
  | "view.showExplorer"
  | "view.showSearch"
  | "view.showRules"
  | "view.showProblems"
  | "view.showOutput"
  | "view.showSettings"
  | "view.showTranspile"
  | "rules.toggleActive"
  | "rules.validateCustom"
  | "rules.saveCustom"
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
  /** Opens the folder picker and, when a folder is chosen, starts the analysis. */
  readonly selectFolder: () => void;
  /** Starts the analysis on the folder already chosen. */
  readonly runAnalysis: () => void;
  readonly cancelAnalysis: () => void;
  /** Closes a tab, asking first when it holds unsaved edits. */
  readonly requestCloseTab: (id: string) => void;
  /** Writing the rule configuration file. */
  readonly rulesActions: RulesActions;
  /** Writes the selected tab's edits back over the original. */
  readonly saveActiveTab: () => void;
  /** Writes every unsaved tab back, one after another. */
  readonly saveAllTabs: () => void;
  /** Whether anything is unsaved, which is what makes the two save commands apply. */
  readonly hasDirty: boolean;
}

/** The rule the active tab describes, or null when the active tab describes none. */
function activeRuleId(workbench: WorkbenchState): string | null {
  const active = workbench.tabs.find((tab) => tab.id === workbench.activeTabId);
  return active?.kind === "rules" ? active.path : null;
}

/** The asset the active tab shows the source of, or null when the active tab shows none. */
function activeSourcePath(workbench: WorkbenchState): string | null {
  const active = workbench.tabs.find((tab) => tab.id === workbench.activeTabId);
  return active?.kind === "source" ? active.path : null;
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
      id: "view.showSearch",
      title: text.command.showSearch,
      category: text.command.categoryView,
      when: () => true,
      run: () => workbenchDispatch({ type: "SHOW_SIDE", view: "search" }),
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
      // Only a COBOL source has a translation, so the command applies to a source tab alone.
      when: () => activeSourcePath(workbench) !== null,
      run: () => {
        const path = activeSourcePath(workbench);
        if (path !== null) {
          workbenchDispatch({ type: "OPEN_TAB", tab: transpileTab(path) });
        }
      },
    },
    {
      id: "rules.toggleActive",
      title: text.command.toggleRule,
      category: text.command.categoryRules,
      when: () => activeRuleId(workbench) !== null,
      run: () => {
        const id = activeRuleId(workbench);
        const entry = project.rules.entries.find((candidate) => candidate.id === id);
        if (id !== null && entry !== undefined) {
          context.rulesActions.setRulesEnabled([id], !entry.enabled);
        }
      },
    },
    {
      id: "rules.validateCustom",
      title: text.command.validateCustomRules,
      category: text.command.categoryRules,
      when: () => workbench.activeTabId === CUSTOM_RULES_TAB_ID,
      run: () => context.rulesActions.validateCustomRules(),
    },
    {
      id: "rules.saveCustom",
      title: text.command.saveCustomRules,
      category: text.command.categoryRules,
      when: () => workbench.activeTabId === CUSTOM_RULES_TAB_ID,
      run: () => context.rulesActions.saveCustomRules(),
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
      when: () => workbench.activeTabId !== null && context.hasDirty,
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

/**
 * Writing the rule configuration file and asking the engine what it made of it.
 *
 * Every edit follows the same route: change the file, write it, then re-run `rules` and keep what
 * comes back. The engine is the single authority on which rules are in effect and at what severity,
 * so the screens never work that out from the file they just wrote.
 *
 * The hook holds no React state of its own: the write queue is module-level and shared, so any screen
 * may call the hook. Reading the file at start-up belongs to useShellStartup, which already resolves
 * the artefact paths.
 */

import { useCallback, useLayoutEffect } from "react";
import type { RulesFile } from "../../../shared/rulesFile";
import { RULES_FILE_VERSION } from "../../../shared/rulesFile";
import { api, errorMessage } from "../api";
import { asWritten } from "../model/customRules";
import { setEnabled, setSeverity } from "../model/rulesView";
import { useProject, useProjectDispatch } from "./projectStore";
import { useRulesDispatch, useRulesState } from "./rulesStore";
import type { Notify } from "./useShellStartup";

export interface RulesActions {
  /** Turns the named rules on or off. One id or a whole category takes the same route. */
  setRulesEnabled(ids: readonly string[], enabled: boolean): void;
  /** Overrides a rule's severity, or removes the override when severity is null. */
  setRuleSeverity(id: string, severity: string | null): void;
  /** Writes the edited custom rules. */
  saveCustomRules(): void;
  /** Asks the engine to judge the file the editor would write, without writing it. */
  validateCustomRules(): void;
}

/**
 * The writes of the rule file, one after another, shared by every screen that calls the hook. Each
 * write is one change applied to the file as the write before it left it, so two edits made in quick
 * succession from two screens (the custom rules saved, then a rule switched off) both reach the disk.
 */
let writing: Promise<void> = Promise.resolve();
/** The file the store holds, as of the last render of a screen that calls the hook. */
let held: RulesFile | null = null;
/** The file as the last write left it, until the store has caught up with it. */
let written: RulesFile | null = null;

export function useRules(notify: Notify): RulesActions {
  const project = useProject();
  const projectDispatch = useProjectDispatch();
  const rules = useRulesState();
  const rulesDispatch = useRulesDispatch();
  const rulesPath = project.outputPaths?.rules ?? null;
  // Before the file has been read the store holds an empty one; a write then would put that empty
  // file in the place of the one on disk.
  const loaded = rules.loaded;
  // After the commit, so a render React discards leaves what the writes go by alone.
  useLayoutEffect(() => {
    if (held !== rules.file) {
      held = rules.file;
      written = null;
    }
  }, [rules.file]);

  /** Writes the changed file and replaces the catalogue with the one the engine derives from it. */
  const persist = useCallback(
    (change: (file: RulesFile) => RulesFile): void => {
      if (rulesPath === null || !loaded) {
        return;
      }
      writing = writing
        .then(async () => {
          const base = written ?? held;
          if (base === null) {
            return;
          }
          const next = change(base);
          await api().writeRules(rulesPath, next);
          written = next;
          rulesDispatch({ type: "SET_FILE", file: next });
          projectDispatch({ type: "RULES_WRITTEN" });
          const catalog = await api().rules({ rulesFile: rulesPath });
          projectDispatch({
            type: "SET_RULES",
            entries: catalog.rules,
            ruleErrors: catalog.ruleErrors,
          });
        })
        .catch((error: unknown) => notify(errorMessage(error), true));
    },
    [rulesPath, loaded, rulesDispatch, projectDispatch, notify],
  );

  const setRulesEnabled = useCallback(
    (ids: readonly string[], enabled: boolean): void => {
      persist((file) => setEnabled(file, ids, enabled));
    },
    [persist],
  );

  const setRuleSeverity = useCallback(
    (id: string, severity: string | null): void => {
      persist((file) => setSeverity(file, id, severity));
    },
    [persist],
  );

  const saveCustomRules = useCallback((): void => {
    if (rules.rawError !== null) {
      return;
    }
    const custom = rules.draft.map(asWritten);
    persist((file) => ({ ...file, custom }));
  }, [persist, rules.draft, rules.rawError]);

  const validateCustomRules = useCallback((): void => {
    if (rules.rawError !== null) {
      // Text that is not JSON has nothing the engine could read; the raw pane already says so.
      return;
    }
    const candidate = JSON.stringify(
      { version: RULES_FILE_VERSION, rules: rules.file.rules, custom: rules.draft.map(asWritten) },
      null,
      2,
    );
    api()
      .validateRules(candidate)
      .then((validation) => rulesDispatch({ type: "SET_VALIDATION", validation }))
      .catch((error: unknown) => notify(errorMessage(error), true));
  }, [rules.file.rules, rules.draft, rules.rawError, rulesDispatch, notify]);

  return { setRulesEnabled, setRuleSeverity, saveCustomRules, validateCustomRules };
}

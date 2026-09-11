/**
 * Writing the rule configuration file and asking the engine what it made of it.
 *
 * Every edit follows the same route: change the file, write it, then re-run `rules` and keep what
 * comes back. The engine is the single authority on which rules are in effect and at what severity,
 * so the screens never work that out from the file they just wrote.
 *
 * These are callbacks only — no state and no effect — so any screen may call the hook. Reading the
 * file at start-up belongs to useShellStartup, which already resolves the artefact paths.
 */

import { useCallback } from "react";
import type { RulesFile } from "../../../shared/rulesFile";
import { RULES_FILE_VERSION } from "../../../shared/rulesFile";
import { api, errorMessage } from "../api";
import { pruned } from "../model/customRules";
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

export function useRules(notify: Notify): RulesActions {
  const project = useProject();
  const projectDispatch = useProjectDispatch();
  const rules = useRulesState();
  const rulesDispatch = useRulesDispatch();
  const rulesPath = project.outputPaths?.rules ?? null;

  /** Writes the file and replaces the catalogue with the one the engine derives from it. */
  const persist = useCallback(
    (next: RulesFile): void => {
      if (rulesPath === null) {
        return;
      }
      api()
        .writeRules(rulesPath, next)
        .then(async () => {
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
    [rulesPath, rulesDispatch, projectDispatch, notify],
  );

  const setRulesEnabled = useCallback(
    (ids: readonly string[], enabled: boolean): void => {
      persist(setEnabled(rules.file, ids, enabled));
    },
    [persist, rules.file],
  );

  const setRuleSeverity = useCallback(
    (id: string, severity: string | null): void => {
      persist(setSeverity(rules.file, id, severity));
    },
    [persist, rules.file],
  );

  const saveCustomRules = useCallback((): void => {
    if (rules.rawError !== null) {
      return;
    }
    persist({ ...rules.file, custom: rules.draft.map(pruned) });
  }, [persist, rules.file, rules.draft, rules.rawError]);

  const validateCustomRules = useCallback((): void => {
    if (rules.rawError !== null) {
      // Text that is not JSON has nothing the engine could read; the raw pane already says so.
      return;
    }
    const candidate = JSON.stringify(
      { version: RULES_FILE_VERSION, rules: rules.file.rules, custom: rules.draft.map(pruned) },
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

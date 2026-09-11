/**
 * The quick fix on a finding the engine can fix.
 *
 * The engine's `fix` never touches an original, so this offers no edit of its own: it opens the fix
 * diff for the asset, where the proposed text sits beside the original. Which rules have a fix comes
 * from the rule catalog (`hasFix`); the GUI keeps no list of its own.
 *
 * The provider is registered once and reads what it needs from module state, because Monaco holds on
 * to the provider it was given and the target changes with every tab.
 */

import type * as monacoApi from "monaco-editor/editor/editor.api";
import { text } from "../../i18n/text";
import { LANGUAGE_ID } from "../../vendor/monarch";

/** The command a quick fix runs. Monaco needs an id; nothing outside this module uses it. */
const COMMAND_ID = "cobolInsight.showFix";

/** The rule ids the engine can produce a fix for, and what to do when one is chosen. */
let fixableRuleIds: ReadonlySet<string> = new Set();
let showFix: ((ruleId: string) => void) | null = null;

/** Points the quick fix at the asset now in view. Called whenever the tab or the catalog changes. */
export function setQuickFixTarget(
  ruleIds: ReadonlySet<string>,
  onShowFix: (ruleId: string) => void,
): void {
  fixableRuleIds = ruleIds;
  showFix = onShowFix;
}

/** The marker's rule id, or null when it carries none. */
function ruleIdOf(marker: monacoApi.editor.IMarkerData): string | null {
  return typeof marker.code === "string" ? marker.code : null;
}

/**
 * The actions offered for the markers under the caret. Exported so the decision can be tested
 * without Monaco: the provider below only wraps it.
 */
export function quickFixActions(
  markers: readonly monacoApi.editor.IMarkerData[],
): monacoApi.languages.CodeAction[] {
  const actions: monacoApi.languages.CodeAction[] = [];
  const seen = new Set<string>();
  for (const marker of markers) {
    const ruleId = ruleIdOf(marker);
    if (ruleId === null || !fixableRuleIds.has(ruleId) || seen.has(ruleId)) {
      continue;
    }
    seen.add(ruleId);
    actions.push({
      title: text.problems.showFix,
      kind: "quickfix",
      diagnostics: [marker],
      command: { id: COMMAND_ID, title: text.problems.showFix, arguments: [ruleId] },
    });
  }
  return actions;
}

let registered = false;

/** Registers the command and the code action provider. Repeated calls do nothing. */
export function registerQuickFix(monaco: typeof monacoApi): void {
  if (registered) {
    return;
  }
  monaco.editor.registerCommand(COMMAND_ID, (_accessor, ruleId: unknown) => {
    showFix?.(String(ruleId));
  });
  monaco.languages.registerCodeActionProvider(LANGUAGE_ID.cobol, {
    provideCodeActions: (_model, _range, context) => ({
      actions: quickFixActions(context.markers),
      dispose: () => undefined,
    }),
  });
  registered = true;
}

/**
 * What to do when a tab is asked to close. Tabs can be closed from the tab strip, from the command
 * palette and from a keyboard chord, so the decision lives here once and every route reads it.
 *
 * This is a decision, not a dialog: an unsaved tab yields "confirm", and the caller opens the in-app
 * modal. window.confirm is not used — it blocks the renderer and cannot be styled or tested.
 */

export type CloseDecision = "close" | "confirm";

/** Whether the tab can be closed outright, or whether the discard has to be confirmed first. */
export function closeDecision(dirty: boolean): CloseDecision {
  return dirty ? "confirm" : "close";
}

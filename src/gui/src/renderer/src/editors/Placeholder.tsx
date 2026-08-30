import type { ReactElement } from "react";
import { text } from "../text";

/**
 * The stand-in for a view a later phase will fill. It exists so every activity entry and every tab
 * kind leads somewhere, rather than leaving a blank region that reads as a bug.
 */
export function Placeholder(): ReactElement {
  return (
    <p className="ci-placeholder" data-testid="placeholder">
      {text.placeholder.notImplemented}
    </p>
  );
}

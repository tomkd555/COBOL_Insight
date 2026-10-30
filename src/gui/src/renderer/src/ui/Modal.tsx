import { useEffect, useId, useRef, type ReactElement, type ReactNode } from "react";

/** The elements the Tab key can stop at. */
const TABBABLE = [
  "button",
  "[href]",
  "input",
  "select",
  "textarea",
  "summary",
  '[tabindex]:not([tabindex="-1"])',
  '[contenteditable="true"]',
].join(", ");

/** An editable region in the Tab order, which some engines report with a tabIndex of -1 all the same. */
const EDITABLE = '[contenteditable="true"]:not([tabindex="-1"])';

export interface ModalProps {
  title: string;
  /** Omitted when the title and the buttons say everything. */
  children?: ReactNode;
  /** The buttons, laid out from left to right. The last one receives focus on opening. */
  actions: ReactNode;
  /** Escape and the backdrop both call this. */
  onDismiss: () => void;
  testId?: string;
  /** Widens the dialog, for a body that carries more to read than a sentence. */
  wide?: boolean;
  /**
   * Where the focus goes on closing when the control that opened the dialog is gone (a dialog raised
   * from the command palette). Called at closing, so it can name what is there by then.
   */
  fallbackFocus?: () => HTMLElement | null;
}

/**
 * The in-app modal, which can be styled and tested and leaves the renderer running.
 *
 * Focus moves into the dialog on opening and is trapped inside it while it is open, so a keyboard
 * user cannot tab out to the shell behind. On closing it goes back to the control that opened the
 * dialog, or, where that control is gone, to the place the caller names.
 */
export function Modal({
  title,
  children,
  actions,
  onDismiss,
  testId,
  wide,
  fallbackFocus,
}: ModalProps): ReactElement {
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const titleId = useId();
  const dismissRef = useRef(onDismiss);
  dismissRef.current = onDismiss;
  const fallbackRef = useRef(fallbackFocus);
  fallbackRef.current = fallbackFocus;

  useEffect(() => {
    const dialog = dialogRef.current;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    // Read at every key press: an action that was disabled on opening (an import with nothing
    // pasted yet) joins the Tab order once it is enabled. The trap counts the controls, links,
    // fields, summaries, elements given a tabindex and editable regions, leaving out the ones that
    // are disabled or not rendered (inside closed details, say), so it knows the true first and last
    // stop.
    const enabled = (): HTMLElement[] =>
      dialog === null
        ? []
        : [...dialog.querySelectorAll<HTMLElement>(TABBABLE)].filter(
            (element) =>
              !element.matches(":disabled") &&
              (element.tabIndex >= 0 || element.matches(EDITABLE)) &&
              // A runtime without checkVisibility counts every element as rendered.
              element.checkVisibility?.() !== false,
          );
    // A disabled last action cannot take focus; the nearest enabled one does, so the first Tab still
    // starts inside the dialog.
    const initial = enabled();
    initial[initial.length - 1]?.focus();

    const onKeyDown = (event: globalThis.KeyboardEvent): void => {
      if (event.key === "Escape") {
        event.preventDefault();
        dismissRef.current();
        return;
      }
      const controls = enabled();
      if (event.key !== "Tab" || controls.length === 0) {
        return;
      }
      const first = controls[0];
      const last = controls[controls.length - 1];
      const at = document.activeElement;
      if (dialog !== null && !dialog.contains(at)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      } else if (event.shiftKey && at === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && at === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown, true);
    return () => {
      document.removeEventListener("keydown", onKeyDown, true);
      // A dialog opened from a control that has since gone (the command palette) found the focus on
      // the document itself, or on an element no longer there.
      const kept = opener !== null && opener !== document.body && opener.isConnected;
      (kept ? opener : (fallbackRef.current?.() ?? null))?.focus();
    };
  }, []);

  return (
    <div className="ci-modal" data-testid={testId}>
      <div className="ci-modal__backdrop" onClick={onDismiss} aria-hidden="true" />
      <div
        ref={dialogRef}
        className={`ci-modal__dialog${wide === true ? " ci-modal__dialog--wide" : ""}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
      >
        <h2 className="ci-modal__title" id={titleId}>
          {title}
        </h2>
        {children === undefined ? null : <div className="ci-modal__body">{children}</div>}
        <div className="ci-modal__actions">{actions}</div>
      </div>
    </div>
  );
}

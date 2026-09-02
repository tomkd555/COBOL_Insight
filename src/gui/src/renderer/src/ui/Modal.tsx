import { useEffect, useRef, type ReactElement, type ReactNode } from "react";

export interface ModalProps {
  title: string;
  /** Omitted when the title and the buttons say everything. */
  children?: ReactNode;
  /** The buttons, laid out from left to right. The last one receives focus on opening. */
  actions: ReactNode;
  /** Escape and the backdrop both call this. */
  onDismiss: () => void;
  testId?: string;
  /** Widens the dialog, for a body that carries something to read rather than a sentence. */
  wide?: boolean;
}

/**
 * The in-app modal. window.confirm is not used: it blocks the renderer, cannot be styled to match
 * the shell, and cannot be exercised from a test.
 *
 * Focus moves into the dialog on opening and is trapped inside it while it is open, so a keyboard
 * user cannot tab out to the shell behind.
 */
export function Modal({ title, children, actions, onDismiss, testId, wide }: ModalProps): ReactElement {
  const dialogRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    const focusable = dialog?.querySelectorAll<HTMLElement>("button, [href], input, select, textarea");
    // A disabled last action (an import with nothing pasted yet) cannot take focus; fall back to
    // the nearest enabled one, so the first Tab still starts inside the dialog.
    const enabled = focusable === undefined ? [] : [...focusable].filter((element) => !element.matches(":disabled"));
    enabled[enabled.length - 1]?.focus();

    const onKeyDown = (event: globalThis.KeyboardEvent): void => {
      if (event.key === "Escape") {
        event.preventDefault();
        onDismiss();
        return;
      }
      if (event.key !== "Tab" || enabled.length === 0) {
        return;
      }
      const first = enabled[0];
      const last = enabled[enabled.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown, true);
    return () => document.removeEventListener("keydown", onKeyDown, true);
  }, [onDismiss]);

  return (
    <div className="ci-modal" data-testid={testId}>
      <div className="ci-modal__backdrop" onClick={onDismiss} aria-hidden="true" />
      <div
        ref={dialogRef}
        className={`ci-modal__dialog${wide === true ? " ci-modal__dialog--wide" : ""}`}
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <h2 className="ci-modal__title">{title}</h2>
        {children === undefined ? null : <div className="ci-modal__body">{children}</div>}
        <div className="ci-modal__actions">{actions}</div>
      </div>
    </div>
  );
}

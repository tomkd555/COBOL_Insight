import { useCallback, useEffect, useRef, useState, type ReactElement } from "react";
import { text } from "../i18n/text";

/** How long a toast stays before it fades on its own, in milliseconds. */
const AUTO_DISMISS_MS = 6000;

/** How long the element is held after it is dismissed, so it can leave. Matches --duration-fast. */
const LEAVE_MS = 100;

export interface ToastMessage {
  readonly id: number;
  readonly text: string;
  readonly failed: boolean;
}

export interface ToastProps {
  messages: readonly ToastMessage[];
  onDismiss: (id: number) => void;
}

/**
 * The transient notifications. They are announced politely rather than assertively, so a message
 * does not interrupt what a screen reader is already saying, and each can also be dismissed by hand
 * because an automatic dismissal alone leaves nothing for a user who needs longer to read.
 */
export function Toast({ messages, onDismiss }: ToastProps): ReactElement {
  /**
   * The toasts on their way out. Removing one from `messages` straight away would take the element
   * with it, and an element that is gone cannot animate; it is marked here instead and dropped once
   * the leaving transition has had its time.
   */
  const [leaving, setLeaving] = useState<ReadonlySet<number>>(new Set());
  /** The ids whose removal is already scheduled. A ref, so the timer is set once per id. */
  const scheduled = useRef<Set<number>>(new Set());

  const dismiss = useCallback(
    (id: number): void => {
      if (scheduled.current.has(id)) {
        return;
      }
      scheduled.current.add(id);
      // The timer is set outside the state updater: an updater may run more than once.
      window.setTimeout(() => onDismiss(id), LEAVE_MS);
      setLeaving((current) => new Set(current).add(id));
    },
    [onDismiss],
  );

  useEffect(() => {
    const timers = messages.map((message) =>
      window.setTimeout(() => dismiss(message.id), AUTO_DISMISS_MS),
    );
    return () => timers.forEach((timer) => window.clearTimeout(timer));
  }, [messages, dismiss]);

  // An id that has left is not held: the counter only ever goes up, so it can never come back.
  useEffect(() => {
    const live = (id: number): boolean => messages.some((each) => each.id === id);
    scheduled.current = new Set([...scheduled.current].filter(live));
    setLeaving((current) => {
      const kept = new Set([...current].filter(live));
      return kept.size === current.size ? current : kept;
    });
  }, [messages]);

  return (
    <div className="ci-toasts" role="status" aria-live="polite" data-testid="toasts">
      {messages.map((message) => (
        <div
          key={message.id}
          className={`ci-toast${message.failed ? " ci-toast--failed" : ""}${
            leaving.has(message.id) ? " ci-toast--leaving" : ""
          }`}
          data-testid={`toast-${message.id}`}
        >
          <span className="ci-toast__text">{message.text}</span>
          <button
            type="button"
            className="ci-toast__close"
            aria-label={text.toast.close}
            onClick={() => dismiss(message.id)}
          >
            <span className="codicon codicon-close" aria-hidden="true" />
          </button>
        </div>
      ))}
    </div>
  );
}

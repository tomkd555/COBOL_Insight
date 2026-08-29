import { useEffect, type ReactElement } from "react";
import { text } from "../text";

/** How long a toast stays before it fades on its own, in milliseconds. */
const AUTO_DISMISS_MS = 6000;

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
  useEffect(() => {
    const timers = messages.map((message) =>
      window.setTimeout(() => onDismiss(message.id), AUTO_DISMISS_MS),
    );
    return () => timers.forEach((timer) => window.clearTimeout(timer));
  }, [messages, onDismiss]);

  return (
    <div className="ci-toasts" role="status" aria-live="polite" data-testid="toasts">
      {messages.map((message) => (
        <div
          key={message.id}
          className={`ci-toast${message.failed ? " ci-toast--failed" : ""}`}
          data-testid={`toast-${message.id}`}
        >
          <span className="ci-toast__text">{message.text}</span>
          <button
            type="button"
            className="ci-toast__close"
            aria-label={text.toast.close}
            onClick={() => onDismiss(message.id)}
          >
            <span className="codicon codicon-close" aria-hidden="true" />
          </button>
        </div>
      ))}
    </div>
  );
}

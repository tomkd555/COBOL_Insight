import { Component, type ErrorInfo, type ReactNode } from "react";
import { text } from "../i18n/text";

export interface ErrorBoundaryProps {
  /** What the boundary stands around. A change of this key takes the boundary out of its failed state. */
  resetKey: string;
  /** What is said in place of the content that could not be drawn. */
  message: string;
  children: ReactNode;
}

interface ErrorBoundaryState {
  /** Why the content could not be drawn; null while it is drawn. */
  reason: string | null;
}

/**
 * Keeps a failure in one part of the window inside that part. React takes the whole window down
 * when a component throws while it renders; the drafts of every open tab live in the stores above
 * the shell, so with the failure held here they are still there to be saved.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  override state: ErrorBoundaryState = { reason: null };

  static getDerivedStateFromError(error: unknown): ErrorBoundaryState {
    return { reason: error instanceof Error ? error.message : String(error) };
  }

  override componentDidCatch(error: unknown, info: ErrorInfo): void {
    // For whoever looks into it: the reason on screen says what failed, the stack says where.
    console.error(error, info.componentStack);
  }

  override componentDidUpdate(previous: ErrorBoundaryProps): void {
    if (previous.resetKey !== this.props.resetKey && this.state.reason !== null) {
      this.setState({ reason: null });
    }
  }

  override render(): ReactNode {
    if (this.state.reason === null) {
      return this.props.children;
    }
    return (
      <div className="ci-source__state ci-source__state--error" role="alert" data-testid="editor-failed">
        <p>{this.props.message}</p>
        <button type="button" className="ci-button" onClick={() => this.setState({ reason: null })}>
          {text.editor.retry}
        </button>
        {/* The reason is the program's own wording, so it is kept out of the way until asked for. */}
        <details>
          <summary>{text.editor.failedReason}</summary>
          <span className="ci-source__reason">{this.state.reason}</span>
        </details>
      </div>
    );
  }
}

/**
 * Where the caret is and which codepage the open file was decoded in, for the status bar.
 *
 * This is kept apart from the workbench store because it changes on every caret move. Folding it in
 * would make every arrow key run the workbench reducer and re-render the whole shell, for two
 * numbers that only the status bar shows.
 *
 * The value and the setter sit in separate contexts, for the same reason: the editor publishes the
 * status but never reads it, so it must not re-render when the caret moves.
 */

import {
  createContext,
  useContext,
  useState,
  type Dispatch,
  type ReactElement,
  type ReactNode,
  type SetStateAction,
} from "react";

export interface EditorStatus {
  /** One-based caret line. */
  readonly line: number;
  /** One-based caret column, in characters. */
  readonly column: number;
  /** The codepage the engine decoded the file in. */
  readonly codepage: string;
  /** Whether that codepage was detected rather than given. */
  readonly detected: boolean;
}

const StatusContext = createContext<EditorStatus | null>(null);
const SetStatusContext = createContext<Dispatch<SetStateAction<EditorStatus | null>> | null>(null);

export function EditorStatusProvider({ children }: { children: ReactNode }): ReactElement {
  const [status, setStatus] = useState<EditorStatus | null>(null);
  return (
    <StatusContext.Provider value={status}>
      <SetStatusContext.Provider value={setStatus}>{children}</SetStatusContext.Provider>
    </StatusContext.Provider>
  );
}

/** The status of the editor in view, or null when no source tab is open. */
export function useEditorStatus(): EditorStatus | null {
  return useContext(StatusContext);
}

/** Publishes the status. The editor calls it on every caret move and on every decode. */
export function useSetEditorStatus(): Dispatch<SetStateAction<EditorStatus | null>> {
  const setStatus = useContext(SetStatusContext);
  if (setStatus === null) {
    throw new Error("useSetEditorStatus must be used inside EditorStatusProvider");
  }
  return setStatus;
}

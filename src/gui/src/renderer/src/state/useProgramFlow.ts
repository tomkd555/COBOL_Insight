/**
 * The paragraphs and flows of the program in view, read from the project file the scan wrote.
 *
 * It is read again whenever the artefact epoch moves, so a scan that finished is picked up without
 * the view asking. While a run is in progress the project file is being written, so the read waits
 * for it and what was read before stays on screen.
 */

import { useEffect, useState } from "react";
import type { ProgramFlow } from "../../../shared/ipc";
import { api, errorMessage } from "../api";
import { structureScanDue, useProject } from "./projectStore";

export type ProgramFlowState =
  | { readonly status: "none" }
  | { readonly status: "loading" }
  | { readonly status: "ready"; readonly flow: ProgramFlow }
  | { readonly status: "error"; readonly message: string };

export interface ProgramFlowView {
  readonly state: ProgramFlowState;
  /**
   * How the flow stands against the assets written since it was read: "reading" while the scan that
   * follows a write is due or under way, "unread" when that scan failed or was stopped, null when
   * nothing has been written.
   */
  readonly stale: "reading" | "unread" | null;
}

const LOADING: ProgramFlowState = { status: "loading" };

export function useProgramFlow(relPath: string): ProgramFlowView {
  const project = useProject();
  const { dbPath, inputDir, runId } = project;
  const running = project.mode === "running";
  // Any write counts: a saved copybook changes the flow of every program that includes it.
  const written = project.structureStale.length > 0;
  /** A scan for what was written is due or under way, and the read is made once it has finished. */
  const due = structureScanDue(project);
  // The path the state was read for is kept with it: this hook stays mounted across a tab switch,
  // and one program's flow must not be shown under another's name while the next read is under way.
  const [held, setHeld] = useState<{ path: string; state: ProgramFlowState }>({
    path: relPath,
    state: { status: "none" },
  });

  useEffect(() => {
    if (dbPath === null || inputDir === null) {
      setHeld({ path: relPath, state: { status: "none" } });
      return;
    }
    if (running || due) {
      return;
    }
    let cancelled = false;
    setHeld((current) =>
      current.path === relPath && current.state.status === "ready"
        ? current
        : { path: relPath, state: LOADING },
    );
    api()
      .readProgramFlow(dbPath, inputDir, relPath)
      .then((flow) => {
        if (cancelled) {
          return;
        }
        // A read that brought back what is already held keeps the held object: the trace and the
        // drawing are built from it, and an equal copy would have both built again for nothing.
        setHeld((current) =>
          current.path === relPath &&
          current.state.status === "ready" &&
          JSON.stringify(current.state.flow) === JSON.stringify(flow)
            ? current
            : { path: relPath, state: { status: "ready", flow } },
        );
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setHeld({ path: relPath, state: { status: "error", message: errorMessage(error) } });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [dbPath, inputDir, relPath, runId, running, due]);

  return {
    state: held.path === relPath ? held.state : LOADING,
    stale: !written ? null : due || running ? "reading" : "unread",
  };
}

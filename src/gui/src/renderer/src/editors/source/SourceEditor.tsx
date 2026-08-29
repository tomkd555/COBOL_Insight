import { useEffect, useRef, useState, type ReactElement } from "react";
import type { DecodeResult } from "../../../../shared/ipc";
import { api, errorMessage } from "../../api";
import { text } from "../../text";
import { useProject } from "../../state/projectStore";
import { useSettings } from "../../state/settingsStore";

export interface SourceEditorProps {
  /** The asset's path, relative to the asset folder. */
  path: string;
  /** The line to reveal on opening, or null. */
  line: number | null;
}

type Load =
  | { status: "loading" }
  | { status: "ready"; result: DecodeResult }
  | { status: "error"; message: string };

/**
 * The source view for one asset.
 *
 * Decoding belongs to the engine: Node has no EBCDIC converter, so the renderer receives text rather
 * than bytes. This phase renders that text read-only in a <pre>; the Monaco editor replaces the body
 * in a later phase, which is why the tab kind is already `source` and the decode path is already the
 * real one.
 */
export function SourceEditor({ path, line }: SourceEditorProps): ReactElement {
  const project = useProject();
  const settings = useSettings();
  const [load, setLoad] = useState<Load>({ status: "loading" });
  const lineRef = useRef<HTMLSpanElement | null>(null);

  const baseDir = project.inputDir;
  const override = project.codepageOverrides[path];
  const dbPath = project.dbPath;

  useEffect(() => {
    if (baseDir === null) {
      return;
    }
    let cancelled = false;
    setLoad({ status: "loading" });
    api()
      .decode({
        baseDir,
        path,
        // An explicit override wins; otherwise the engine reads the codepage the scan recorded.
        codepage: override ?? (settings.defaultEncoding === "" ? undefined : settings.defaultEncoding),
        db: dbPath ?? undefined,
      })
      .then((result) => {
        if (cancelled) return;
        setLoad(
          result.error === ""
            ? { status: "ready", result }
            : { status: "error", message: result.error },
        );
      })
      .catch((error: unknown) => {
        if (!cancelled) setLoad({ status: "error", message: errorMessage(error) });
      });
    return () => {
      cancelled = true;
    };
  }, [baseDir, path, override, dbPath, settings.defaultEncoding]);

  // Bring the requested line into view once the text is there to scroll to.
  useEffect(() => {
    if (load.status === "ready") {
      lineRef.current?.scrollIntoView({ block: "center" });
    }
  }, [load.status, line]);

  if (load.status === "loading") {
    return <p className="ci-source__state">{text.sourceView.loading}</p>;
  }
  if (load.status === "error") {
    return (
      <p className="ci-source__state ci-source__state--error" role="alert">
        {text.sourceView.error}
        <span className="ci-source__reason">{load.message}</span>
      </p>
    );
  }

  const lines = load.result.text.split("\n");

  return (
    <div className="ci-source" data-testid={`source-${path}`}>
      <div className="ci-source__meta">
        <span>
          {text.sourceView.codepage} {load.result.codepage}
          {load.result.detected ? `（${text.sourceView.detected}）` : ""}
        </span>
        <span>{text.sourceView.lines(lines.length)}</span>
      </div>
      <pre className="ci-source__body">
        {lines.map((content, index) => (
          <span
            key={index}
            ref={line === index + 1 ? lineRef : undefined}
            className={`ci-source__line${line === index + 1 ? " ci-source__line--target" : ""}`}
          >
            <span className="ci-source__number">{index + 1}</span>
            {content}
            {"\n"}
          </span>
        ))}
      </pre>
    </div>
  );
}

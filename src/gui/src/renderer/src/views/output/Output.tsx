import { useEffect, useRef, type ReactElement } from "react";
import { text } from "../../i18n/text";
import { useProject, useProjectDispatch } from "../../state/projectStore";

/**
 * The run log. New lines are appended at the bottom and the view scrolls to follow them, so the
 * stage in progress stays in sight during a long analysis. With no folder it stays empty: the
 * welcome view is where that is said.
 */
export function Output(): ReactElement {
  const project = useProject();
  const dispatch = useProjectDispatch();
  const endRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: "end" });
  }, [project.runLog.length]);

  if (project.runLog.length === 0) {
    return project.inputDir === null ? (
      <div className="ci-output" />
    ) : (
      <p className="ci-output__state">{text.empty.notAnalysed}</p>
    );
  }

  return (
    <div className="ci-output">
      <div className="ci-output__toolbar">
        <button
          type="button"
          className="ci-button ci-button--quiet"
          onClick={() => dispatch({ type: "CLEAR_LOG" })}
          data-testid="output-clear"
        >
          {text.output.clear}
        </button>
      </div>
      <ol className="ci-output__lines" aria-label={text.output.title}>
        {project.runLog.map((entry) => (
          <li
            key={entry.id}
            className={`ci-output__line${entry.failed ? " ci-output__line--failed" : ""}`}
            data-testid={`log-${entry.id}`}
          >
            <span className="ci-output__time">{entry.time}</span>
            <span className="ci-output__text">{entry.text}</span>
          </li>
        ))}
      </ol>
      <div ref={endRef} />
    </div>
  );
}

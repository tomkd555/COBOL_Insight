import { useReducer, type ReactElement } from "react";
import { api, engineFailure, errorMessage } from "../../api";
import { text } from "../../i18n/text";
import { useProject } from "../../state/projectStore";
import type { Notify } from "../../state/useShellStartup";
import {
  INITIAL_REPORT_STATE,
  REPORT_SANDBOX,
  deriveReportView,
  reportArtifactPaths,
  reportFileName,
  reportPathOf,
  reportReducer,
  type ReportFormat,
} from "../../model/report";

export interface ReportEditorProps {
  /** How a failed write reaches the user. */
  notify: Notify;
}

/**
 * The report editor. One `report` run writes both the HTML and the text report beside the project
 * file; the button that started it decides which of the two is shown.
 *
 * The HTML is shown in an iframe with an empty `sandbox`, so the report the engine generated cannot
 * run a script or reach the renderer. Writing a copy elsewhere goes through main's save dialog; the
 * engine's own output stays where it was written.
 */
export function ReportEditor({ notify }: ReportEditorProps): ReactElement {
  const project = useProject();
  const [state, dispatch] = useReducer(reportReducer, INITIAL_REPORT_STATE);

  const { inputDir, dbPath, outputPaths } = project;
  // report is generated from the SARIF pair the whole-folder lint writes, so it needs one of those
  // to have finished for this folder — findings on screen can have come from a scoped run, whose
  // pair is another one. With dbPath forced to null the state machine already renders notAnalysed.
  const analysed = project.lastWholeFolderRunId > 0;
  const view = deriveReportView(project.mode, inputDir, analysed ? dbPath : null, state);
  // The report is generated from the SARIF pair the whole-folder lint writes. A scoped run since
  // then is on screen but not in that pair, so the difference is named rather than hidden.
  const scopedSinceWholeRun = project.lastScopedRunId > project.lastWholeFolderRunId;

  async function generate(format: ReportFormat): Promise<void> {
    if (dbPath === null || outputPaths === null) {
      return;
    }
    const paths = reportArtifactPaths(dbPath);
    dispatch({ type: "GENERATE", format });
    try {
      const finished = await api().run({
        subcommand: "report",
        request: {
          db: paths.db,
          // The whole-folder pair. A scoped run writes its own, so what is generated here always
          // covers the same ground as the last analysis of the whole folder.
          sarifFile: outputPaths.sarif,
          sqlSarifFile: outputPaths.sqlSarif,
          htmlFile: paths.html,
          textFile: paths.text,
        },
      });
      const crashed = engineFailure(finished);
      if (crashed !== null) {
        // The report files beside the project file are the previous run's; showing them would
        // present an older report as the one just asked for.
        throw new Error(crashed);
      }
      const path = reportPathOf(format, paths);
      const content = await api().readReport({ path, kind: format });
      dispatch({ type: "READY", format, content, path });
    } catch (error) {
      dispatch({ type: "FAILED", format, message: errorMessage(error) });
    }
  }

  function exportCopy(): void {
    if (state.status !== "ready") {
      return;
    }
    api()
      .saveAs({ fileName: reportFileName(state.format), text: state.content })
      .then((path) => {
        if (path !== null) {
          notify(text.report.saved(path));
        }
      })
      .catch((error: unknown) => notify(errorMessage(error), true));
  }

  const busy = view.kind === "generating";
  const disabled = busy || dbPath === null || outputPaths === null || !analysed;

  return (
    <div className="ci-report" data-testid="report-editor">
      <div className="ci-report__toolbar">
        <button
          type="button"
          className="ci-button"
          disabled={disabled}
          onClick={() => void generate("html")}
          data-testid="report-generate-html"
        >
          {text.report.generateHtml}
        </button>
        <button
          type="button"
          className="ci-button"
          disabled={disabled}
          onClick={() => void generate("text")}
          data-testid="report-generate-text"
        >
          {text.report.generateText}
        </button>
        <button
          type="button"
          className="ci-button"
          aria-label={text.report.exportLabel}
          disabled={state.status !== "ready"}
          onClick={exportCopy}
          data-testid="report-export"
        >
          {text.report.export}
        </button>
      </div>

      {view.kind === "no-project" ? null : (
        <p className="ci-report__note" data-testid="report-note">
          {text.report.wholeProject}
          {scopedSinceWholeRun ? ` ${text.report.scopedSinceWholeRun}` : ""}
        </p>
      )}

      {view.kind === "no-project" ? (
        <p className="ci-report__state">
          {inputDir === null ? text.empty.noFolder : text.empty.notAnalysed}
        </p>
      ) : null}
      {/* Once there is something to report on, the two generate buttons are the whole state. */}
      {view.kind === "generating" ? (
        <p className="ci-report__state" data-testid="report-generating">
          {text.report.generating}
        </p>
      ) : null}
      {view.kind === "failed" ? (
        <p className="ci-report__state ci-report__state--error" role="alert">
          {text.report.failed(view.message)}
        </p>
      ) : null}

      {view.kind === "ready" && state.status === "ready" ? (
        <>
          <p className="ci-report__path">{`${text.report.path}: ${state.path}`}</p>
          {state.format === "html" ? (
            <iframe
              className="ci-report__frame"
              title={text.report.preview}
              sandbox={REPORT_SANDBOX}
              srcDoc={state.content}
              data-testid="report-html"
            />
          ) : (
            <pre className="ci-report__text" data-testid="report-text">
              {state.content}
            </pre>
          )}
        </>
      ) : null}
    </div>
  );
}

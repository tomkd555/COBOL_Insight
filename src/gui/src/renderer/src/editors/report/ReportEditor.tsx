import { useReducer, type ReactElement } from "react";
import { api, errorMessage } from "../../api";
import { text } from "../../i18n/text";
import { useProject } from "../../state/projectStore";
import { useSettings } from "../../state/settingsStore";
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
  const settings = useSettings();
  const [state, dispatch] = useReducer(reportReducer, INITIAL_REPORT_STATE);

  const { inputDir, dbPath } = project;
  const view = deriveReportView(project.mode, inputDir, dbPath, state);

  async function generate(format: ReportFormat): Promise<void> {
    if (inputDir === null || dbPath === null) {
      return;
    }
    const paths = reportArtifactPaths(dbPath);
    dispatch({ type: "GENERATE", format });
    try {
      const outputs = await api().outputPaths();
      await api().run({
        subcommand: "report",
        request: {
          inputDir,
          copybookPaths: [...settings.copybookPaths],
          codepageOverrides: project.codepageOverrides,
          rulesFile: outputs.rules,
          db: paths.db,
          htmlFile: paths.html,
          textFile: paths.text,
        },
      });
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
  const disabled = busy || inputDir === null || dbPath === null;

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

import { useId, useMemo, useState, type ReactElement } from "react";
import { text } from "../../i18n/text";
import { api, errorMessage } from "../../api";
import { Modal } from "../../ui/Modal";
import { importDirSegments, importFileName, importRelPath } from "../../../../shared/importPath";
import { clipColumns, columnRuler, displayWidth } from "../../model/importModel";
import { RECORD_COLUMNS } from "../../model/columns";
import { normalizeScreenPaste, type PanelKind } from "../../model/pasteScreen";
import { useProjectDispatch } from "../../state/projectStore";

/** The column range the fields accept. The upper bound leaves room beyond a fixed-format record. */
const MIN_COLUMN = 1;
const MAX_COLUMN = 200;

/**
 * How the pasted text is read: recognised from its title row (an SEU display also from the shape of
 * its rows), as one named panel, or cut to a column range typed by hand.
 */
type KindChoice = "auto" | PanelKind | "manual";

const PANEL_KINDS: readonly PanelKind[] = ["ispf-edit", "ispf-browse", "seu"];

/** Where the import stands. `confirm` means a file of that name is already there. */
type Status =
  | { kind: "idle" }
  | { kind: "saving" }
  /** `relPath` is the file as the folder holds it; `asked` the destination the save was pressed for. */
  | { kind: "confirm"; relPath: string; asked: string }
  | { kind: "saved"; relPath: string; lineCount: number }
  | { kind: "error"; message: string };

/** The column a field holds, or null while it holds no number of the accepted range. */
function columnOf(raw: string): number | null {
  const parsed = /^\d+$/.test(raw.trim()) ? Number(raw.trim()) : Number.NaN;
  return parsed >= MIN_COLUMN && parsed <= MAX_COLUMN ? parsed : null;
}

export interface ImportDialogProps {
  inputDir: string;
  onClose: () => void;
}

/**
 * Importing a source file from a terminal screen: the source is read out of the pasted text and
 * written into the asset folder. A copy of an ISPF or SEU panel is recognised and its line numbers,
 * panel rows and repeated lines are taken away; any other text is cut to a column range.
 *
 * The overwrite confirmation is tied to the destination it was raised for. Changing the folder or
 * the name after the confirmation appeared changes the destination, and an unconfirmed file must
 * not be overwritten by the confirmation of another one.
 */
export function ImportDialog({ inputDir, onClose }: ImportDialogProps): ReactElement {
  const [pasted, setPasted] = useState("");
  const [destDir, setDestDir] = useState("");
  const [fileName, setFileName] = useState("");
  // The fields hold what was typed, so a field can be emptied on the way to another number.
  const [columnFromText, setColumnFromText] = useState("1");
  const [columnToText, setColumnToText] = useState(String(RECORD_COLUMNS));
  const columnFrom = columnOf(columnFromText);
  const columnTo = columnOf(columnToText);
  const [kind, setKind] = useState<KindChoice>("auto");
  const [status, setStatus] = useState<Status>({ kind: "idle" });
  const projectDispatch = useProjectDispatch();

  const screen = useMemo(
    () => (kind === "manual" ? null : normalizeScreenPaste(pasted, kind === "auto" ? undefined : kind)),
    [pasted, kind],
  );
  // Text that is no panel is cut to the column range, as is everything when the range is asked for.
  const panel = screen !== null && screen.kind !== "plain" ? screen : null;
  const lines = useMemo(
    () =>
      panel !== null
        ? [...panel.lines]
        : columnFrom === null || columnTo === null
          ? []
          : clipColumns(pasted, columnFrom, columnTo),
    [panel, pasted, columnFrom, columnTo],
  );
  const relPath = importRelPath(destDir, fileName);
  const dirUsable = importDirSegments(destDir) !== null;
  const nameRefused = fileName.trim() !== "" && importFileName(fileName) === null;
  // With text to save and no name for it, 保存 is off; the field says which entry it waits for.
  const nameMissing = fileName.trim() === "" && lines.length > 0;
  const dirErrorId = useId();
  const nameErrorId = useId();
  const columnsTyped = columnFrom !== null && columnTo !== null;
  const columnsValid = panel !== null || (columnsTyped && columnFrom <= columnTo);
  const pending = status.kind === "confirm" && status.asked !== relPath ? { kind: "idle" as const } : status;
  /**
   * What main reported for the entries as they stood is taken away once one of them is edited: the
   * folder, the name, the screen kind, either column or the pasted text.
   */
  const edited = (): void =>
    setStatus((current) => (current.kind === "error" ? { kind: "idle" } : current));
  const saving = pending.kind === "saving";
  const canSave = lines.length > 0 && relPath !== null && columnsValid && !saving;

  const ruler = useMemo(
    () => columnRuler(Math.max(...lines.map(displayWidth), 0)),
    [lines],
  );

  const save = (overwrite: boolean): void => {
    setStatus({ kind: "saving" });
    const asked = relPath ?? "";
    // Inside the chain, so a call that throws at once is reported like one that is rejected.
    Promise.resolve()
      .then(() => api().importSource({ inputDir, destDir, fileName, lines, overwrite }))
      .then((result) => {
        setStatus(
          result.status === "exists"
            ? { kind: "confirm", relPath: result.relPath, asked }
            : { kind: "saved", relPath: result.relPath, lineCount: result.lineCount },
        );
        if (result.status === "written") {
          // The folder now holds content the last scan has not read; marking it stale has the next
          // scan read it.
          projectDispatch({ type: "SOURCE_SAVED", path: result.relPath });
        }
      })
      .catch((error: unknown) => setStatus({ kind: "error", message: errorMessage(error) }));
  };

  const actions =
    pending.kind === "saved" ? (
      <button type="button" className="ci-button" onClick={onClose} data-testid="import-close">
        {text.modal.close}
      </button>
    ) : (
      <>
        <button
          type="button"
          className="ci-button ci-button--primary"
          disabled={!canSave}
          onClick={() => save(false)}
          data-testid="import-save"
        >
          {saving ? text.import.saving : text.import.save}
        </button>
        <button
          type="button"
          className="ci-button"
          onClick={onClose}
          data-testid="import-cancel"
        >
          {text.import.cancel}
        </button>
      </>
    );

  return (
    <Modal
      title={text.import.title}
      testId="import-dialog"
      onDismiss={onClose}
      actions={actions}
      wide
    >
      <div className="ci-import">
        <div className="ci-import__form">
          {pending.kind === "error" ? (
            <p className="ci-strip ci-strip--error" role="alert">
              {pending.message}
            </p>
          ) : null}
          {pending.kind === "saved" ? (
            <p className="ci-strip ci-strip--ok" role="status" data-testid="import-saved">
              {text.import.saved(pending.relPath, pending.lineCount)}
            </p>
          ) : null}
          {pending.kind === "confirm" ? (
            <div className="ci-strip ci-strip--error" data-testid="import-confirm">
              <p role="alert">{text.import.exists(pending.relPath)}</p>
              <button
                type="button"
                className="ci-button ci-button--danger"
                onClick={() => save(true)}
                data-testid="import-overwrite"
              >
                {text.import.overwrite}
              </button>
            </div>
          ) : null}

          <label className="ci-form__field">
            <span className="ci-form__label">{text.import.destDir}</span>
            <input
              className="ci-input"
              placeholder={text.import.destDirPlaceholder}
              value={destDir}
              onChange={(event) => {
                setDestDir(event.target.value);
                edited();
              }}
              aria-invalid={!dirUsable}
              aria-describedby={dirUsable ? undefined : dirErrorId}
              data-testid="import-destdir"
            />
            {dirUsable ? null : (
              <span className="ci-form__error" id={dirErrorId}>
                {text.import.destDirInvalid}
              </span>
            )}
          </label>

          <label className="ci-form__field">
            <span className="ci-form__label">{text.import.fileName}</span>
            <input
              className="ci-input"
              placeholder={text.import.fileNamePlaceholder}
              value={fileName}
              onChange={(event) => {
                setFileName(event.target.value);
                edited();
              }}
              aria-invalid={nameRefused}
              aria-describedby={nameRefused || nameMissing ? nameErrorId : undefined}
              data-testid="import-filename"
            />
            {/* Each field carries its own error, and 保存 stays off until both hold. The destination
                is stated where it differs from the name just typed. */}
            {nameRefused ? (
              <span className="ci-form__error" id={nameErrorId}>
                {text.import.fileNameInvalid}
              </span>
            ) : nameMissing ? (
              <span className="ci-form__note" id={nameErrorId} data-testid="import-name-required">
                {text.import.fileNameRequired}
              </span>
            ) : relPath === null || relPath === fileName ? null : (
              <span className="ci-form__note">{text.import.destination(relPath)}</span>
            )}
          </label>

          <label className="ci-form__field">
            <span className="ci-form__label">{text.import.kind}</span>
            <select
              className="ci-select"
              value={kind}
              onChange={(event) => {
                setKind(event.target.value as KindChoice);
                edited();
              }}
              data-testid="import-kind"
            >
              <option value="auto">{text.import.kindAuto}</option>
              {PANEL_KINDS.map((each) => (
                <option key={each} value={each}>
                  {text.import.kindName[each]}
                </option>
              ))}
              <option value="manual">{text.import.kindManual}</option>
            </select>
          </label>

          {panel === null ? (
            <div className="ci-form__field">
              <span className="ci-form__label">{text.import.columns}</span>
              <div className="ci-import__columns">
                <label className="ci-form__field">
                  <span className="ci-form__label">{text.import.columnFrom}</span>
                  <input
                    className="ci-input"
                    type="number"
                    min={MIN_COLUMN}
                    max={MAX_COLUMN}
                    value={columnFromText}
                    onChange={(event) => {
                      setColumnFromText(event.target.value);
                      edited();
                    }}
                    data-testid="import-column-from"
                  />
                </label>
                <label className="ci-form__field">
                  <span className="ci-form__label">{text.import.columnTo}</span>
                  <input
                    className="ci-input"
                    type="number"
                    min={MIN_COLUMN}
                    max={MAX_COLUMN}
                    value={columnToText}
                    onChange={(event) => {
                      setColumnToText(event.target.value);
                      edited();
                    }}
                    data-testid="import-column-to"
                  />
                </label>
              </div>
              {columnsValid ? null : (
                <span className="ci-form__error">
                  {columnsTyped
                    ? text.import.columnsInvalid
                    : text.import.columnsRequired(MIN_COLUMN, MAX_COLUMN)}
                </span>
              )}
            </div>
          ) : null}
        </div>

        <div className="ci-import__body">
          <label className="ci-form__field">
            <span className="ci-form__label">{text.import.paste}</span>
            <textarea
              className="ci-import__paste"
              spellCheck={false}
              value={pasted}
              onChange={(event) => {
                setPasted(event.target.value);
                edited();
              }}
              data-testid="import-paste"
            />
          </label>
          <span className="ci-form__label">{text.import.preview}</span>
          {lines.length === 0 ? null : (
            <pre className="ci-import__preview" data-testid="import-preview">
              {`${ruler.tens}\n${ruler.ones}\n${lines.join("\n")}`}
            </pre>
          )}
          <span className="ci-form__note">{text.import.lineCount(lines.length)}</span>
          {kind === "auto" && panel === null && lines.length > 0 ? (
            <span className="ci-form__note" data-testid="import-read-plain">
              {text.import.readPlain}
            </span>
          ) : null}
          {panel === null || lines.length === 0 ? null : (
            <span className="ci-form__note" data-testid="import-read">
              {text.import.read(
                text.import.kindName[panel.kind as PanelKind],
                panel.removed.chrome,
                panel.removed.duplicates,
              )}
            </span>
          )}
          {panel !== null && panel.conflicts > 0 ? (
            <span className="ci-form__error" role="status" data-testid="import-conflicts">
              {text.import.conflicts(panel.conflicts)}
            </span>
          ) : null}
          {panel !== null && panel.hidden > 0 ? (
            <span className="ci-form__error" role="status" data-testid="import-hidden">
              {text.import.hidden(panel.hidden)}
            </span>
          ) : null}
        </div>
      </div>
    </Modal>
  );
}

import { useMemo, useState, type ReactElement } from "react";
import { text } from "../../i18n/text";
import { api, errorMessage } from "../../api";
import { Modal } from "../../ui/Modal";
import { clipColumns, columnRuler, displayWidth, importRelPath } from "../../model/importModel";
import type { ImportAssetKind } from "../../../../shared/ipc";

/** The asset kinds an import can be filed under, in the order the dialog offers them. */
const KINDS: readonly { kind: ImportAssetKind; label: string }[] = [
  { kind: "cobol", label: text.assetType.cobol },
  { kind: "copybook", label: text.assetType.copybook },
  { kind: "jcl", label: text.assetType.jcl },
  { kind: "bms", label: text.assetType.bms },
];

/** The column range the fields accept. The upper bound leaves room beyond a fixed-format record. */
const MIN_COLUMN = 1;
const MAX_COLUMN = 200;

/** Where the import stands. `confirm` means a file of that name is already there. */
type Status =
  | { kind: "idle" }
  | { kind: "saving" }
  | { kind: "confirm"; relPath: string }
  | { kind: "saved"; relPath: string; lineCount: number }
  | { kind: "error"; message: string };

function columnValue(raw: string, current: number): number {
  const parsed = Number.parseInt(raw, 10);
  return Number.isNaN(parsed) ? current : Math.min(Math.max(parsed, MIN_COLUMN), MAX_COLUMN);
}

export interface ImportDialogProps {
  inputDir: string;
  onClose: () => void;
}

/**
 * Importing a source file from a terminal screen: the pasted text is cut to a column range and
 * written into the asset folder.
 *
 * The overwrite confirmation is tied to the destination it was raised for. Changing the kind, the
 * folder or the name after the confirmation appeared changes the destination, and an unconfirmed
 * file must not be overwritten by the confirmation of another one.
 */
export function ImportDialog({ inputDir, onClose }: ImportDialogProps): ReactElement {
  const [pasted, setPasted] = useState("");
  const [kind, setKind] = useState<ImportAssetKind>("cobol");
  const [destDir, setDestDir] = useState("");
  const [fileName, setFileName] = useState("");
  const [columnFrom, setColumnFrom] = useState(1);
  const [columnTo, setColumnTo] = useState(80);
  const [status, setStatus] = useState<Status>({ kind: "idle" });

  const lines = useMemo(
    () => clipColumns(pasted, columnFrom, columnTo),
    [pasted, columnFrom, columnTo],
  );
  const relPath = importRelPath(kind, destDir, fileName);
  const columnsValid = columnFrom <= columnTo;
  const pending = status.kind === "confirm" && status.relPath !== relPath ? { kind: "idle" as const } : status;
  const saving = pending.kind === "saving";
  const canSave = lines.length > 0 && relPath !== null && columnsValid && !saving;

  const ruler = useMemo(
    () => columnRuler(Math.max(...lines.map(displayWidth), 0)),
    [lines],
  );

  const save = (overwrite: boolean): void => {
    setStatus({ kind: "saving" });
    api()
      .importSource({ inputDir, kind, destDir, fileName, lines, overwrite })
      .then((result) => {
        setStatus(
          result.status === "exists"
            ? { kind: "confirm", relPath: result.relPath }
            : { kind: "saved", relPath: result.relPath, lineCount: result.lineCount },
        );
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

          <div className="ci-form__field">
            <span className="ci-form__label">{text.import.kind}</span>
            <div className="ci-chips" role="radiogroup" aria-label={text.import.kind}>
              {KINDS.map((option) => (
                <button
                  key={option.kind}
                  type="button"
                  role="radio"
                  aria-checked={kind === option.kind}
                  className={`ci-chip${kind === option.kind ? " ci-chip--on" : ""}`}
                  onClick={() => setKind(option.kind)}
                  data-testid={`import-kind-${option.kind}`}
                >
                  {option.label}
                </button>
              ))}
            </div>
          </div>

          <label className="ci-form__field">
            <span className="ci-form__label">{text.import.destDir}</span>
            <input
              className="ci-input"
              placeholder={text.import.destDirPlaceholder}
              value={destDir}
              onChange={(event) => setDestDir(event.target.value)}
              data-testid="import-destdir"
            />
          </label>

          <label className="ci-form__field">
            <span className="ci-form__label">{text.import.fileName}</span>
            <input
              className="ci-input"
              placeholder={text.import.fileNamePlaceholder}
              value={fileName}
              onChange={(event) => setFileName(event.target.value)}
              data-testid="import-filename"
            />
            {/* The destination is worth stating only where it is not the name just typed. */}
            {relPath === null ? (
              <span className="ci-form__error">{text.import.fileNameInvalid}</span>
            ) : relPath === fileName ? null : (
              <span className="ci-form__note">{text.import.destination(relPath)}</span>
            )}
          </label>

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
                  value={columnFrom}
                  onChange={(event) => setColumnFrom(columnValue(event.target.value, columnFrom))}
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
                  value={columnTo}
                  onChange={(event) => setColumnTo(columnValue(event.target.value, columnTo))}
                  data-testid="import-column-to"
                />
              </label>
            </div>
            {columnsValid ? null : (
              <span className="ci-form__error">{text.import.columnsInvalid}</span>
            )}
          </div>
        </div>

        <div className="ci-import__body">
          <label className="ci-form__field">
            <span className="ci-form__label">{text.import.paste}</span>
            <textarea
              className="ci-import__paste"
              spellCheck={false}
              value={pasted}
              onChange={(event) => setPasted(event.target.value)}
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
        </div>
      </div>
    </Modal>
  );
}

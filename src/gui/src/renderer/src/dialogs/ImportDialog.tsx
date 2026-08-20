import { useEffect, useMemo, useRef, useState, type ReactElement } from "react";
import { Button } from "../components/Button";
import { TextInput } from "../components/TextInput";
import { ASSET_KIND_SPECS, importRelPath, type ImportAssetKind } from "../../../shared/assetImport";
import { messageOf } from "../services/analysis";
import { ImportPreview } from "./ImportPreview";
import { clipColumns } from "./importModel";

/** 取込の実行状態。confirm は同名のファイルがあり、上書きの確認を待っていることを表す。 */
type ImportStatus =
  | { kind: "idle" }
  | { kind: "saving" }
  | { kind: "confirm"; relPath: string }
  | { kind: "error"; message: string };

const IDLE: ImportStatus = { kind: "idle" };

/** 桁として受ける範囲。固定形式の記録長に余裕を見た上限とする。 */
const MIN_COLUMN = 1;
const MAX_COLUMN = 200;

/** 桁の入力値を整数へ正規化する。数値として読めない入力は現在値を保つ。 */
function columnValue(raw: string, current: number): number {
  const parsed = Number.parseInt(raw, 10);
  if (Number.isNaN(parsed)) {
    return current;
  }
  return Math.min(Math.max(parsed, MIN_COLUMN), MAX_COLUMN);
}

export interface ImportDialogProps {
  /** 取込先の資産フォルダ。 */
  inputDir: string;
  onClose: () => void;
  /** 書き出しを終えたときに呼ぶ。呼び手は再解析を促す。 */
  onSaved: (relPath: string, lineCount: number) => void;
}

/**
 * 端末取込のダイアログ。端末エミュレータの画面から複写した本文を受け取り、桁位置を指定して
 * 切り出し、資産フォルダの下のソースファイルとして保存する。
 *
 * 端末の表示では行番号欄・コマンド欄が本文の左に並ぶため、そのまま貼り付けると桁がずれる。
 * 取り込む桁を指定して切り出し、保存前にプレビューの桁定規で確かめる形にしてある。書き出しは
 * main の importSource が資産フォルダ配下に限って行い、renderer はファイルへ直接触れない。
 */
export function ImportDialog({ inputDir, onClose, onSaved }: ImportDialogProps): ReactElement {
  const [text, setText] = useState("");
  const [kind, setKind] = useState<ImportAssetKind>("cobol");
  const [destDir, setDestDir] = useState("");
  const [fileName, setFileName] = useState("");
  const [columnFrom, setColumnFrom] = useState(1);
  const [columnTo, setColumnTo] = useState(80);
  const [status, setStatus] = useState<ImportStatus>(IDLE);
  const dialogRef = useRef<HTMLDivElement>(null);

  // 開いた直後の焦点をダイアログの中へ入れる。外に残ると Tab が背後の画面を巡る。
  useEffect(() => {
    dialogRef.current?.querySelector<HTMLElement>("input, textarea, button")?.focus();
  }, []);

  const lines = useMemo(() => clipColumns(text, columnFrom, columnTo), [text, columnFrom, columnTo]);
  const relPath = importRelPath(kind, destDir, fileName);
  const columnsValid = columnFrom <= columnTo;
  /**
   * 上書きの確認は、確認を出した保存先に対してのみ有効とする。確認の表示中に種別・保存先・
   * ファイル名を変えると保存先が変わるため、そのままでは確認を経ていないファイルを上書きしてしまう。
   */
  const pending = status.kind === "confirm" && status.relPath !== relPath ? IDLE : status;
  const saving = pending.kind === "saving";
  const canSave = lines.length > 0 && relPath !== null && columnsValid && !saving;

  async function save(overwrite: boolean): Promise<void> {
    setStatus({ kind: "saving" });
    try {
      const result = await window.cobolInsight.importSource({
        inputDir,
        kind,
        destDir,
        fileName,
        lines,
        overwrite,
      });
      if (result.status === "exists") {
        setStatus({ kind: "confirm", relPath: result.relPath });
        return;
      }
      setStatus(IDLE);
      onSaved(result.relPath, result.lineCount);
    } catch (error) {
      setStatus({ kind: "error", message: messageOf(error) });
    }
  }

  return (
    <div className="ci-modal" role="presentation" onClick={onClose}>
      <div
        ref={dialogRef}
        className="ci-modal__panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="ci-import-title"
        onClick={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            event.stopPropagation();
            onClose();
          }
        }}
      >
        <div className="ci-modal__head">
          <h2 className="ci-modal__title" id="ci-import-title">
            端末取込
          </h2>
          <button type="button" className="ci-modal__close" aria-label="閉じる" onClick={onClose}>
            ×
          </button>
        </div>

        <div className="ci-modal__body ci-import">
          <div className="ci-import__form">
            {pending.kind === "error" ? (
              <div className="ci-banner ci-banner--error" role="alert">
                保存できませんでした。{pending.message}
              </div>
            ) : null}
            {pending.kind === "confirm" ? (
              <div className="ci-banner ci-banner--error">
                <p role="alert">
                  {pending.relPath} は既にあります。上書きすると元の内容は失われます。
                </p>
                <div className="ci-import__confirm-actions">
                  <Button variant="primary" onClick={() => void save(true)}>
                    上書きする
                  </Button>
                  <Button onClick={() => setStatus(IDLE)}>やめる</Button>
                </div>
              </div>
            ) : null}

            <div className="ci-import__field">
              <p className="ci-import__label" id="ci-import-kind-label">
                資産の種別
              </p>
              <div className="ci-import__kinds" role="group" aria-labelledby="ci-import-kind-label">
                {ASSET_KIND_SPECS.map((spec) => (
                  <Button
                    key={spec.kind}
                    variant={spec.kind === kind ? "primary" : "default"}
                    aria-pressed={spec.kind === kind}
                    onClick={() => setKind(spec.kind)}
                  >
                    {spec.label}
                  </Button>
                ))}
              </div>
              <p className="ci-import__note">
                種別は解析の対象を決めません。解析エンジンは内容から種別を判定します。コピー句だけは
                COPY 文から引けるよう、拡張子 .cpy を補います。
              </p>
            </div>

            <div className="ci-import__field">
              <label className="ci-import__label" htmlFor="ci-import-dest">
                保存先
              </label>
              <TextInput
                id="ci-import-dest"
                placeholder="資産フォルダの直下"
                value={destDir}
                onChange={(event) => setDestDir(event.target.value)}
              />
              <p className="ci-import__note">資産フォルダからの相対パス。空欄なら直下に置きます。</p>
            </div>

            <div className="ci-import__field">
              <label className="ci-import__label" htmlFor="ci-import-name">
                ファイル名
              </label>
              <TextInput
                id="ci-import-name"
                placeholder="SYK001.cbl"
                value={fileName}
                invalid={fileName !== "" && relPath === null}
                aria-describedby="ci-import-name-note"
                onChange={(event) => setFileName(event.target.value)}
              />
              <p className="ci-import__note" id="ci-import-name-note">
                {relPath === null
                  ? 'パス区切りと記号 \\ / : * ? " < > | は使えません。'
                  : `保存先: ${relPath}`}
              </p>
            </div>

            <div className="ci-import__field">
              <p className="ci-import__label">取り込む桁</p>
              <div className="ci-import__columns">
                <label className="ci-import__column">
                  開始
                  <TextInput
                    aria-label="開始桁"
                    type="number"
                    min={MIN_COLUMN}
                    max={MAX_COLUMN}
                    value={columnFrom}
                    onChange={(event) =>
                      setColumnFrom(columnValue(event.target.value, columnFrom))
                    }
                  />
                </label>
                <label className="ci-import__column">
                  終了
                  <TextInput
                    aria-label="終了桁"
                    type="number"
                    min={MIN_COLUMN}
                    max={MAX_COLUMN}
                    value={columnTo}
                    onChange={(event) => setColumnTo(columnValue(event.target.value, columnTo))}
                  />
                </label>
              </div>
              <p className="ci-import__note">
                {columnsValid
                  ? "端末の表示桁で数えます(1起点・両端を含む)。行番号欄ごと複写したときは、本文が始まる桁を開始桁にします。"
                  : "終了桁は開始桁以上にしてください。"}
              </p>
            </div>

            <Button variant="primary" disabled={!canSave} onClick={() => void save(false)}>
              {saving ? "保存しています…" : "保存"}
            </Button>
            <p className="ci-import__note">
              保存は UTF-8 で行います。解析エンジンは保存したファイルの文字コードを自動で判別します。
            </p>
          </div>

          <div className="ci-import__body">
            <label className="ci-import__label" htmlFor="ci-import-paste">
              端末から複写した本文
            </label>
            <textarea
              id="ci-import-paste"
              className="ci-import__paste"
              placeholder="端末エミュレータの画面で範囲を選んで複写し、ここへ貼り付けます。"
              spellCheck={false}
              value={text}
              onChange={(event) => setText(event.target.value)}
            />
            <ImportPreview lines={lines} />
          </div>
        </div>
      </div>
    </div>
  );
}

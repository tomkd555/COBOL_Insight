import { useMemo, useState, type ReactElement } from "react";
import { Button } from "../../components/Button";
import { EmptyState } from "../../components/EmptyState";
import { RunningIndicator } from "../../components/RunningIndicator";
import { TextInput } from "../../components/TextInput";
import { useAppDispatch, useAppState } from "../../state/AppStateContext";
import { ASSET_KIND_SPECS, importRelPath } from "../../../../shared/assetImport";
import { SCREEN_META } from "../screenMeta";
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

/** 例外・非 Error 値から表示用の文言を取り出す。 */
function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** 桁の入力値を整数へ正規化する。数値として読めない入力は現在値を保つ。 */
function columnValue(raw: string, current: number): number {
  const parsed = Number.parseInt(raw, 10);
  if (Number.isNaN(parsed)) {
    return current;
  }
  return Math.min(Math.max(parsed, MIN_COLUMN), MAX_COLUMN);
}

/**
 * 端末取込の画面。IBM Personal Communications をはじめとする端末エミュレータの画面から複写した
 * 本文を受け取り、桁位置を指定して切り出し、資産フォルダ配下のソースファイルとして保存する。
 *
 * 端末の画面には行番号欄・コマンド欄が本文の左に並ぶため、そのまま貼り付けると桁がずれる。
 * 取り込む桁を指定して切り出し、保存前にプレビューの桁定規で確かめる形にしてある。書き出しは
 * main の importSource が資産フォルダ配下に限って行い、renderer はファイルへ直接触れない。
 */
export function ImportScreen(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();
  const { inputDir } = state.project;
  const [status, setStatus] = useState<ImportStatus>(IDLE);

  const lines = useMemo(
    () => clipColumns(state.importText, state.importColumnFrom, state.importColumnTo),
    [state.importText, state.importColumnFrom, state.importColumnTo],
  );
  const relPath = importRelPath(state.importKind, state.importFileName);
  const columnsValid = state.importColumnFrom <= state.importColumnTo;
  /**
   * 上書きの確認は、確認を出した保存先に対してのみ有効とする。確認の表示中に種別・ファイル名を
   * 変えると保存先が変わるため、そのままでは確認を経ていないファイルを上書きしてしまう。
   */
  const pending = status.kind === "confirm" && status.relPath !== relPath ? IDLE : status;

  /** 資産フォルダを選び、プロジェクトの入力フォルダとして共有する。 */
  async function selectFolder(): Promise<void> {
    try {
      const selected = await window.cobolInsight.selectInputFolder();
      if (selected === null) return;
      dispatch({ type: "SET_PROJECT", project: { inputDir: selected } });
    } catch (error) {
      setStatus({ kind: "error", message: messageOf(error) });
    }
  }

  /** 切り出した本文を資産フォルダへ書き出す。同名のファイルがあれば上書きの確認を挟む。 */
  async function save(overwrite: boolean): Promise<void> {
    if (inputDir === null) return;
    setStatus({ kind: "saving" });
    try {
      const result = await window.cobolInsight.importSource({
        inputDir,
        kind: state.importKind,
        fileName: state.importFileName,
        lines,
        overwrite,
      });
      if (result.status === "exists") {
        setStatus({ kind: "confirm", relPath: result.relPath });
        return;
      }
      setStatus(IDLE);
      dispatch({ type: "SET_IMPORT", patch: { importText: "", importFileName: "" } });
      dispatch({
        type: "SHOW_TOAST",
        message: `${result.relPath} へ ${result.lineCount} 行を保存しました。資産一覧の「▶ 解析実行」で解析へ反映します。`,
      });
    } catch (error) {
      setStatus({ kind: "error", message: messageOf(error) });
    }
  }

  if (state.mode === "running") {
    // 走査の最中に資産フォルダへ書き足すと、その回の資産一覧へ入るかどうかが定まらない。
    return <RunningIndicator title={SCREEN_META.import.runningTitle} activeStage={state.runStage} />;
  }

  if (inputDir === null) {
    return (
      <div className="ci-import ci-import--placeholder">
        <h3 className="ci-import__title">端末取込</h3>
        {pending.kind === "error" ? (
          <div className="ci-banner ci-banner--error" role="alert">
            資産フォルダを選べなかった。{pending.message}
          </div>
        ) : null}
        <EmptyState
          icon="＋"
          title="保存先の資産フォルダが選ばれていません"
          description="端末エミュレータから複写した本文は、資産フォルダの下の種別ごとのフォルダへ保存する。まず保存先の資産フォルダを選ぶ。"
          actionLabel="資産フォルダを選ぶ"
          onAction={() => void selectFolder()}
          note="空のフォルダでもよい。取込の際に種別ごとのフォルダを作る。"
        />
      </div>
    );
  }

  const saving = pending.kind === "saving";
  const canSave = lines.length > 0 && relPath !== null && columnsValid && !saving;

  return (
    <div className="ci-import">
      <div className="ci-import__form">
        <h3 className="ci-import__title">端末取込</h3>
        <p className="ci-import__intro">
          端末エミュレータの画面から複写した本文を、桁位置を指定して切り出し、資産フォルダへ保存する。
        </p>

        {pending.kind === "error" ? (
          <div className="ci-banner ci-banner--error" role="alert">
            保存できなかった。{pending.message}
          </div>
        ) : null}
        {pending.kind === "confirm" ? (
          <div className="ci-banner ci-banner--error">
            <p className="ci-import__confirm-text" role="alert">
              {pending.relPath} は既にある。上書きすると元の内容は失われる。
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
                variant={spec.kind === state.importKind ? "primary" : "default"}
                aria-pressed={spec.kind === state.importKind}
                onClick={() => dispatch({ type: "SET_IMPORT", patch: { importKind: spec.kind } })}
              >
                {spec.label}
              </Button>
            ))}
          </div>
          <p className="ci-import__note">
            走査は資産フォルダ直下の bms・cobol・copy・copybook・jcl を見る。種別に応じたフォルダと拡張子で保存する。
          </p>
        </div>

        <div className="ci-import__field">
          <label className="ci-import__label" htmlFor="ci-import-name">
            ファイル名
          </label>
          <TextInput
            id="ci-import-name"
            placeholder="SYK001"
            value={state.importFileName}
            onChange={(event) =>
              dispatch({ type: "SET_IMPORT", patch: { importFileName: event.target.value } })
            }
          />
          <p className="ci-import__note">
            {relPath === null
              ? "保存先: ファイル名を入れる（パス区切りと記号 \\ / : * ? \" < > | は使えない）。"
              : `保存先: ${relPath}`}
          </p>
        </div>

        <div className="ci-import__field">
          <p className="ci-import__label">取り込む桁</p>
          <div className="ci-import__columns">
            <label className="ci-import__column">
              開始
              <TextInput
                id="ci-import-col-from"
                aria-label="開始桁"
                type="number"
                min={MIN_COLUMN}
                max={MAX_COLUMN}
                value={state.importColumnFrom}
                onChange={(event) =>
                  dispatch({
                    type: "SET_IMPORT",
                    patch: {
                      importColumnFrom: columnValue(event.target.value, state.importColumnFrom),
                    },
                  })
                }
              />
            </label>
            <label className="ci-import__column">
              終了
              <TextInput
                id="ci-import-col-to"
                aria-label="終了桁"
                type="number"
                min={MIN_COLUMN}
                max={MAX_COLUMN}
                value={state.importColumnTo}
                onChange={(event) =>
                  dispatch({
                    type: "SET_IMPORT",
                    patch: {
                      importColumnTo: columnValue(event.target.value, state.importColumnTo),
                    },
                  })
                }
              />
            </label>
          </div>
          <p className="ci-import__note">
            {columnsValid
              ? "端末の表示桁で数える（1起点・両端を含む）。行番号欄を含めて複写したときは、本文が始まる桁を開始桁にする。"
              : "終了桁は開始桁以上にする。"}
          </p>
        </div>

        <Button variant="primary" disabled={!canSave} onClick={() => void save(false)}>
          {saving ? "保存している…" : "資産フォルダへ保存する"}
        </Button>
        <p className="ci-import__note">
          保存は UTF-8 で行う。解析エンジンは保存したファイルの文字コードを自動で判別する。
        </p>
      </div>

      <div className="ci-import__body">
        <label className="ci-import__label" htmlFor="ci-import-paste">
          端末から複写した本文
        </label>
        <textarea
          id="ci-import-paste"
          className="ci-import__paste"
          placeholder="端末エミュレータの画面で範囲を選んで複写し、ここへ貼り付ける。"
          spellCheck={false}
          value={state.importText}
          onChange={(event) =>
            dispatch({ type: "SET_IMPORT", patch: { importText: event.target.value } })
          }
        />
        <ImportPreview lines={lines} />
      </div>
    </div>
  );
}

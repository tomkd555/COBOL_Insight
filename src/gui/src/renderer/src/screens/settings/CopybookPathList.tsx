import type { ReactElement } from "react";
import { Button } from "../../components/Button";
import { TextInput } from "../../components/TextInput";

export interface CopybookPathListProps {
  paths: readonly string[];
  /** 追加入力の現在値。 */
  draft: string;
  onDraftChange: (value: string) => void;
  onMove: (index: number, delta: number) => void;
  onRemove: (index: number) => void;
  onAdd: () => void;
  disabled: boolean;
  /** パスごとの実在確認結果。未確認のパスはキーを持たず、警告を出さない。 */
  existence: Readonly<Record<string, boolean>>;
  /** 直前の追加操作で実在しないと分かったときの警告文。無ければ null。 */
  addWarning: string | null;
}

/** 追加入力の警告文の id。入力欄の aria-describedby から指すため、画面内で一意にする。 */
const ADD_WARN_ID = "ci-paths-add-warn";

/**
 * コピー句探索パスの一覧と編集(design scSet のコピー句検索パス)。上から順に探索するため、
 * 並び順そのものが設定値である。並びの変更・削除・追加を行い、結果は engine の
 * `--copybook-path` へ同じ順序で渡る(利用者向けの文言では内部の引数名を出さない)。
 */
export function CopybookPathList({
  paths,
  draft,
  onDraftChange,
  onMove,
  onRemove,
  onAdd,
  disabled,
  existence,
  addWarning,
}: CopybookPathListProps): ReactElement {
  return (
    <div className="ci-paths">
      {paths.length === 0 ? (
        <p className="ci-paths__empty">
          コピー句探索パスはまだありません。指定しないとき、解析エンジンは資産フォルダ配下の
          copybook・copy を探します。
        </p>
      ) : (
        <ol className="ci-paths__list">
          {paths.map((path, index) => (
            <li key={path} className="ci-paths__item">
              <span className="ci-paths__index">{index + 1}</span>
              <span className="ci-paths__path">
                {path}
                {existence[path] === false ? (
                  <span className="ci-paths__missing"> ⚠ フォルダが見つかりません</span>
                ) : null}
              </span>
              <Button
                aria-label={`${path} を1つ上へ`}
                disabled={disabled || index === 0}
                onClick={() => onMove(index, -1)}
              >
                ↑
              </Button>
              <Button
                aria-label={`${path} を1つ下へ`}
                disabled={disabled || index === paths.length - 1}
                onClick={() => onMove(index, 1)}
              >
                ↓
              </Button>
              <Button
                aria-label={`${path} を削除`}
                disabled={disabled}
                onClick={() => onRemove(index)}
              >
                ✕
              </Button>
            </li>
          ))}
        </ol>
      )}
      <div className="ci-paths__add">
        <label className="ci-field ci-paths__add-field">
          追加するパス
          <TextInput
            className="ci-paths__input"
            placeholder="例: D:\資産\copylib2"
            value={draft}
            disabled={disabled}
            invalid={addWarning !== null}
            aria-describedby={addWarning === null ? undefined : ADD_WARN_ID}
            onChange={(event) => onDraftChange(event.target.value)}
          />
        </label>
        <Button disabled={disabled} onClick={onAdd}>
          <span aria-hidden="true">＋</span> 追加
        </Button>
      </div>
      {addWarning === null ? null : (
        <p id={ADD_WARN_ID} className="ci-paths__add-warn" role="alert">
          ⚠ {addWarning}
        </p>
      )}
    </div>
  );
}

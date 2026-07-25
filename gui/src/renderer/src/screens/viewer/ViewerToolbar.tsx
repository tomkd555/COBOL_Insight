import type { ReactElement } from "react";
import { Button } from "../../components/Button";
import type { ViewerFileOption } from "./viewerModel";

export interface ViewerToolbarProps {
  /** 表示中のファイル(資産の相対パス)。未選択は空文字。 */
  file: string;
  options: readonly ViewerFileOption[];
  onFileChange: (file: string) => void;
  /** ジャンプ元の説明。ジャンプで開いていないときは null。 */
  from: string | null;
  /** ジャンプ元の画面へ戻る操作。戻り先が判らないときは null。 */
  onBack: (() => void) | null;
  /** 戻り先の画面名。 */
  backLabel: string | null;
}

/** ペインの色分けが何を表すかの凡例。ペインが実際に描く強調だけを並べる。 */
const LEGEND: readonly { readonly modifier: string; readonly label: string }[] = [
  { modifier: "linked", label: "対応行リンク" },
  { modifier: "focus", label: "ジャンプ先の行" },
  { modifier: "noted", label: "直訳不能の注記がある行" },
  { modifier: "identification", label: "識別欄（73〜80桁）" },
];

/**
 * ソースビューアの上部ツールバー(design:351-362)。ファイル選択・ジャンプ元の提示・凡例を置く。
 * ジャンプで開いた場合は遷移元を示し、そこへ戻る導線を添える。
 */
export function ViewerToolbar({
  file,
  options,
  onFileChange,
  from,
  onBack,
  backLabel,
}: ViewerToolbarProps): ReactElement {
  return (
    <div className="ci-viewer__toolbar">
      <label className="ci-viewer__file-label" htmlFor="ci-viewer-file">
        表示するファイル
      </label>
      <select
        id="ci-viewer-file"
        className="ci-viewer__file-select"
        value={file}
        onChange={(event) => onFileChange(event.target.value)}
      >
        <option value="">（選択してください）</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      {from === null ? null : (
        <div className="ci-viewer__from" role="status">
          <span className="ci-viewer__from-text">{from}</span>
          {onBack === null || backLabel === null ? null : (
            <Button className="ci-viewer__back" onClick={onBack}>
              {`← ${backLabel}へ戻る`}
            </Button>
          )}
        </div>
      )}
      <div className="ci-viewer__spacer" />
      <ul className="ci-viewer__legend">
        {LEGEND.map((entry) => (
          <li key={entry.modifier} className="ci-viewer__legend-item">
            <span
              className={`ci-viewer__swatch ci-viewer__swatch--${entry.modifier}`}
              aria-hidden="true"
            />
            {entry.label}
          </li>
        ))}
      </ul>
    </div>
  );
}

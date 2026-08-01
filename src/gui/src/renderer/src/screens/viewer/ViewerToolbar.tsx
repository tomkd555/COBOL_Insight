import type { ReactElement } from "react";
import { Button } from "../../components/Button";
import { SEVERITY_META, SEVERITY_ORDER } from "../../components/severity";
import type { ViewerFileOption } from "./viewerModel";

export interface ViewerToolbarProps {
  /** 表示中の資産(相対パス)。未選択は空文字。 */
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

/**
 * ペインの色分けが何を表すかの凡例。ペインが実際に描く強調だけを並べる。
 * 指摘の重大度は色見本ではなく記号(●◆■▲)で示し、コード面のグリフと同じ表し方にそろえる。
 */
const LEGEND: readonly { readonly modifier: string; readonly label: string }[] = [
  { modifier: "linked", label: "対応行リンク" },
  { modifier: "focus", label: "ジャンプ先の行" },
  { modifier: "noted", label: "直訳不能の注記がある行" },
  { modifier: "identification", label: "識別欄（73〜80桁）" },
];

/**
 * ソースビューアの上部ツールバー(design scSrc)。資産選択・ジャンプ元の提示・凡例を置く。
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
        表示する資産
      </label>
      <select
        id="ci-viewer-file"
        className="ci-viewer__file-select"
        value={file}
        onChange={(event) => onFileChange(event.target.value)}
      >
        <option value="">（未選択）</option>
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
              {`${backLabel}へ戻る`}
            </Button>
          )}
        </div>
      )}
      <div className="ci-viewer__spacer" />
      <ul className="ci-viewer__legend" aria-label="ハイライトの凡例">
        {LEGEND.map((entry) => (
          <li key={entry.modifier} className="ci-viewer__legend-item">
            <span
              className={`ci-viewer__swatch ci-viewer__swatch--${entry.modifier}`}
              aria-hidden="true"
            />
            {entry.label}
          </li>
        ))}
        <li className="ci-viewer__legend-item ci-viewer__legend-heading">指摘のある行</li>
        {SEVERITY_ORDER.map((severity) => (
          <li key={severity} className="ci-viewer__legend-item">
            <span className={`ci-viewer__sev ci-viewer__sev--${severity}`} aria-hidden="true">
              {SEVERITY_META[severity].symbol}
            </span>
            {SEVERITY_META[severity].label}
          </li>
        ))}
      </ul>
    </div>
  );
}

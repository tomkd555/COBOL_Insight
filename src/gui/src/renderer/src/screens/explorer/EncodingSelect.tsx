import type { ReactElement } from "react";
import { ENCODING_OPTIONS } from "./assetView";

export interface EncodingSelectProps {
  /** 現在値。ENCODING_OPTIONS のいずれか(自動判定は検出結果に対応する選択肢)。 */
  value: string;
  onChange: (value: string) => void;
  options?: readonly string[];
}

/**
 * 文字コード選択欄。自動判定(SJIS/UTF-8)・EBCDIC の推定(CP930/CP939)・手動
 * (SJIS/UTF-8/EBCDIC CP930・CP939)から選ぶ。手動指定は自動判定より優先し、次回の解析実行で
 * 反映する。可視ラベルと aria-label を与える。
 */
export function EncodingSelect({ value, onChange, options = ENCODING_OPTIONS }: EncodingSelectProps): ReactElement {
  return (
    <div className="ci-encsel">
      <label className="ci-encsel__label" htmlFor="ci-encsel-select">
        文字コード（手動指定が自動判定より優先）
      </label>
      <select
        id="ci-encsel-select"
        className="ci-encsel__select"
        aria-label="文字コード"
        value={value}
        onChange={(event) => onChange(event.target.value)}
      >
        {options.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    </div>
  );
}

import type { ReactElement } from "react";
import { FilterChip } from "../../components/FilterChip";
import { TextInput } from "../../components/TextInput";
import { SEVERITY_META, SEVERITY_ORDER, type Severity } from "../../components/severity";
import { severityAllowed, type FindingOption } from "./findingsModel";

export interface FindingsToolbarProps {
  /** 重大度ごとの件数(チップ末尾に表示)。 */
  counts: Record<Severity, number>;
  /** 重大度チップの ON/OFF。 */
  severity: Record<Severity, boolean>;
  /** 設定の重大度しきい値。これより低い重大度のチップは操作できない。 */
  threshold: Severity;
  onToggleSeverity: (severity: Severity) => void;
  ruleValue: string;
  ruleOptions: readonly FindingOption[];
  onRuleChange: (value: string) => void;
  fileValue: string;
  fileOptions: readonly FindingOption[];
  onFileChange: (value: string) => void;
  text: string;
  onTextChange: (value: string) => void;
  /** 件数の要約文。 */
  summary: string;
  onGoReport: () => void;
}

/**
 * 一覧上部のツールバー。重大度チップ(件数付・記号で二重符号化)・ルール選択・ファイル選択・
 * 内容テキスト検索・要約・「レポート出力 →」を並べる。指摘一覧と SQL助言で共有する。
 *
 * 重大度チップは設定のしきい値が許した範囲の中でだけ絞り込める。しきい値より低い重大度は
 * 一覧に出ないため、そのチップは操作できない状態にし、件数は隠れている件数として残す。
 */
export function FindingsToolbar({
  counts,
  severity,
  threshold,
  onToggleSeverity,
  ruleValue,
  ruleOptions,
  onRuleChange,
  fileValue,
  fileOptions,
  onFileChange,
  text,
  onTextChange,
  summary,
  onGoReport,
}: FindingsToolbarProps): ReactElement {
  return (
    <div className="ci-findings__toolbar">
      <div className="ci-findings__sev-chips" role="group" aria-label="重大度フィルタ">
        {SEVERITY_ORDER.map((sev) => {
          const meta = SEVERITY_META[sev];
          const allowed = severityAllowed(sev, threshold);
          return (
            <FilterChip
              key={sev}
              label={meta.label}
              count={counts[sev]}
              active={allowed && severity[sev]}
              symbol={meta.symbol}
              symbolColorVar={meta.colorVar}
              disabled={!allowed}
              onClick={() => onToggleSeverity(sev)}
            />
          );
        })}
      </div>
      <select
        className="ci-findings__select"
        aria-label="ルールで絞り込み"
        value={ruleValue}
        onChange={(event) => onRuleChange(event.target.value)}
      >
        {ruleOptions.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      <select
        className="ci-findings__select"
        aria-label="ファイルで絞り込み"
        value={fileValue}
        onChange={(event) => onFileChange(event.target.value)}
      >
        {fileOptions.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      <TextInput
        aria-label="内容で検索"
        placeholder="内容で検索"
        value={text}
        onChange={(event) => onTextChange(event.target.value)}
      />
      <div className="ci-findings__spacer" />
      <span className="ci-findings__summary">{summary}</span>
      <button type="button" className="ci-btn ci-btn--default" onClick={onGoReport}>
        レポート出力 →
      </button>
    </div>
  );
}

import type { KeyboardEvent, ReactElement } from "react";
import type { SarifFinding } from "../../../../shared/engine-api";
import { SeverityBadge } from "../../components/SeverityBadge";
import type { FindingRow, SortColumn, SortState } from "./findingsModel";

export interface FindingsTableProps {
  rows: readonly FindingRow[];
  sort: SortState;
  onSortChange: (column: SortColumn) => void;
  /** 行の活性化。指摘一覧・SQL助言のいずれも選択だけを行い、ジャンプは別の明示的な操作に委ねる。 */
  onActivateRow: (row: FindingRow) => void;
  /** 行を活性化したときに起きることの説明(title と各行の aria-label へ与える)。 */
  rowHint: string;
  /** 表の名前(支援技術へ伝える)。 */
  label: string;
  /** 選択中の指摘。行の強調に使う。選択の概念を持たない画面は渡さない。 */
  selectedFinding?: SarifFinding | null;
  /**
   * ファイル・行のセルをソースへのジャンプ操作にする。渡した画面だけがセルをボタンにし、
   * 渡さない画面(詳細ペインでジャンプする SQL助言)は従来通りの文字表示のままにする。
   */
  onJumpRow?: (row: FindingRow) => void;
  /** フィルタで 0 件になったときのメッセージ(全件は存在する場合)。 */
  noHitMessage: string;
}

interface SortHeaderProps {
  label: string;
  column: SortColumn;
  sort: SortState;
  onSortChange: (column: SortColumn) => void;
}

/**
 * ソート可能な列見出し。ソート状態は aria-sort で列見出し自身へ与え、見出しの中のボタンは
 * ソートの実行だけを担う(aria-pressed をソート状態へ流用しない)。同じ見出しを再度押すと
 * 向きが反転し、別の見出しを押すと昇順から始める。重大度は 高→中→低→警告 の順を昇順とする。
 */
function SortHeader({ label, column, sort, onSortChange }: SortHeaderProps): ReactElement {
  const active = sort.column === column;
  const ariaSort: "ascending" | "descending" | "none" = !active
    ? "none"
    : sort.direction === "asc"
      ? "ascending"
      : "descending";
  return (
    <th role="columnheader" scope="col" className="ci-findings-table__col" aria-sort={ariaSort}>
      <button
        type="button"
        className="ci-findings-table__sort"
        aria-label={`${label}で並べ替え`}
        onClick={() => onSortChange(column)}
      >
        {label}
        {active ? (
          <span className="ci-findings-table__arrow" aria-hidden="true">
            {sort.direction === "asc" ? " ▲" : " ▼"}
          </span>
        ) : null}
      </button>
    </th>
  );
}

/**
 * 指摘の表。列は 重大度/ルール/ファイル/行/内容(根拠)/修正案。重大度・ルール・ファイル・行の
 * 4 列は見出しクリックでソートする。行は Enter・Space でも活性化でき、活性化は選択だけを行う
 * (ソースへのジャンプは含まない)。onJumpRow を渡した画面だけ、ファイル・行のセルがソースへ
 * ジャンプする明示的なボタンになる。
 *
 * CSS で行をグリッドとして並べるため table 要素の既定の表示を外している。表示を外すと
 * 支援技術へ表として伝わらなくなるため、table/rowgroup/row/columnheader/cell の role を明示する。
 */
export function FindingsTable({
  rows,
  sort,
  onSortChange,
  onActivateRow,
  rowHint,
  label,
  selectedFinding = null,
  onJumpRow,
  noHitMessage,
}: FindingsTableProps): ReactElement {
  function onRowKeyDown(event: KeyboardEvent<HTMLTableRowElement>, row: FindingRow): void {
    if (event.key !== "Enter" && event.key !== " ") return;
    event.preventDefault();
    onActivateRow(row);
  }

  return (
    <div className="ci-findings-table">
      <table role="table" className="ci-findings-table__table" aria-label={label}>
        <thead role="rowgroup" className="ci-findings-table__head-group">
          <tr role="row" className="ci-findings-table__head">
            <SortHeader label="重大度" column="sev" sort={sort} onSortChange={onSortChange} />
            <SortHeader label="ルール" column="rule" sort={sort} onSortChange={onSortChange} />
            <SortHeader label="ファイル" column="file" sort={sort} onSortChange={onSortChange} />
            <SortHeader label="行" column="line" sort={sort} onSortChange={onSortChange} />
            <th role="columnheader" scope="col" className="ci-findings-table__col">
              内容（根拠）
            </th>
            <th role="columnheader" scope="col" className="ci-findings-table__col">
              修正案
            </th>
          </tr>
        </thead>
        <tbody role="rowgroup" className="ci-findings-table__body">
          {rows.map((row, index) => {
            const { finding } = row;
            const selected = selectedFinding !== null && finding === selectedFinding;
            const classes = ["ci-findings-table__row"];
            if (selected) classes.push("ci-findings-table__row--selected");
            return (
              <tr
                // 同一位置に同一ルールの指摘が複数あっても衝突しないよう、並び順の位置を含める。
                key={`${index}:${finding.ruleId}:${finding.file}:${finding.startLine}:${finding.startColumn}`}
                role="row"
                className={classes.join(" ")}
                tabIndex={0}
                // 選択は aria-current で示す。aria-selected は grid の選択規約に属し、
                // 矢印キーによるセル移動を伴わない表では用いない。
                aria-current={selected ? true : undefined}
                aria-label={`${finding.ruleId} ${finding.file}:${finding.startLine} ― ${rowHint}`}
                title={rowHint}
                onClick={() => onActivateRow(row)}
                onKeyDown={(event) => onRowKeyDown(event, row)}
              >
                <td role="cell" className="ci-findings-table__sev">
                  <SeverityBadge severity={row.severity} />
                </td>
                <td role="cell" className="ci-findings-table__rule">
                  <span className="ci-findings-table__rule-id">{finding.ruleId}</span>{" "}
                  <span className="ci-findings-table__rule-name">{row.ruleName}</span>
                </td>
                <td role="cell" className="ci-findings-table__file">
                  {finding.file}
                </td>
                <td role="cell">
                  {onJumpRow === undefined ? (
                    <span className="ci-findings-table__line">{finding.startLine}</span>
                  ) : (
                    <button
                      type="button"
                      className="ci-findings-table__line ci-findings-table__line--jump"
                      aria-label={`${finding.file}:${finding.startLine} のソースへジャンプ`}
                      onClick={(event) => {
                        // 行のクリックは選択だけを行うため、ボタンの押下がそこへ伝わらないようにする。
                        event.stopPropagation();
                        onJumpRow(row);
                      }}
                    >
                      {finding.startLine}
                    </button>
                  )}
                </td>
                <td role="cell" className="ci-findings-table__note">
                  {finding.message}
                </td>
                <td role="cell" className="ci-findings-table__fix">
                  {row.hasFix ? <span className="ci-findings-table__fix-badge">差分あり</span> : null}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {rows.length === 0 ? <p className="ci-findings-table__no-hit">{noHitMessage}</p> : null}
    </div>
  );
}

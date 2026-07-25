import type { AriaAttributes, CSSProperties, ReactElement } from "react";

export interface FilterChipProps {
  label: string;
  /** 件数を末尾に薄色で表示する。省略時は表示しない。 */
  count?: number;
  /** 選択(有効)状態か。選択状態は色に依存せず ARIA 属性へ反映する。 */
  active?: boolean;
  /**
   * 単一選択の一群に属するチップか。真ならラジオ(role=radio・aria-checked)として振る舞い、
   * 偽なら独立したトグル(aria-pressed)として振る舞う。既定はトグル。
   */
  single?: boolean;
  onClick?: () => void;
  /**
   * タブ移動の順序。ラジオグループの一員として使う場合、選択中だけ 0・他は -1 を与えて
   * グループ全体を 1 つのタブストップにする(ChipRadioGroup が与える)。
   */
  tabIndex?: number;
  /** 重大度チップ等の先頭記号(●/◆/■/▲)。 */
  symbol?: string;
  /** 記号の色として用いるデザイントークン参照(例 var(--ci-sev-high))。 */
  symbolColorVar?: string;
  /** 絞り込みの対象になり得ないチップ(押しても結果が変わらないもの)を操作不能にする。 */
  disabled?: boolean;
}

/** 選択状態を表す ARIA 属性。単一選択はラジオ、複数選択はトグルボタンとして表す。 */
type SelectionAttributes = Pick<AriaAttributes, "aria-checked" | "aria-pressed"> & { role?: "radio" };

/**
 * フィルタチップ。件数付きのボタンとして、資産種別・重大度などの絞り込みに使う。
 * 単一選択(種別チップ)は role=radio と aria-checked、複数選択(重大度チップ)は aria-pressed で
 * 選択状態を表し、色だけに依存しない。先頭記号(任意)で重大度チップにも使える。
 */
export function FilterChip({
  label,
  count,
  active = false,
  single = false,
  onClick,
  tabIndex,
  symbol,
  symbolColorVar,
  disabled = false,
}: FilterChipProps): ReactElement {
  const classes = ["ci-chip"];
  if (active) classes.push("ci-chip--active");
  const symbolStyle: CSSProperties | undefined = symbolColorVar ? { color: symbolColorVar } : undefined;
  const selection: SelectionAttributes = single
    ? { role: "radio", "aria-checked": active }
    : { "aria-pressed": active };
  return (
    <button
      type="button"
      className={classes.join(" ")}
      tabIndex={tabIndex}
      disabled={disabled}
      {...selection}
      onClick={onClick}
    >
      {symbol ? (
        <span className="ci-chip__symbol" style={symbolStyle} aria-hidden="true">
          {symbol}
        </span>
      ) : null}
      <span className="ci-chip__label">{label}</span>
      {count !== undefined ? <span className="ci-chip__count">{count}</span> : null}
    </button>
  );
}

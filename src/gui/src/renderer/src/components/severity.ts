/**
 * 重大度モデル。色 + 記号の二重符号化(色覚非依存)を単一の正として定義する。
 * 色はデザイントークン(--ci-sev-*)への参照、記号は文字そのものを持つ。
 * 記号は 高=● / 中=◆ / 低=■ / 推奨=▲ とし、画面デザイン(design/COBOL Insight.dc.html)と同じ割当を用いる。
 */

export type Severity = "high" | "medium" | "low" | "warning";

export interface SeverityMeta {
  /** 重大度の識別子。 */
  readonly severity: Severity;
  /** 画面表示ラベル(高/中/低/推奨)。 */
  readonly label: string;
  /** 色覚非依存の記号(●/◆/■/▲)。色と併せて重大度を二重符号化する。 */
  readonly symbol: string;
  /** 記号色として用いるデザイントークン参照。 */
  readonly colorVar: string;
  /** BEM 修飾子の語幹(ci-severity-badge--high 等)。 */
  readonly modifier: Severity;
}

/** 重大度の並び順(高→中→低→推奨)。ソートやしきい値比較の基準にする。 */
export const SEVERITY_ORDER: readonly Severity[] = ["high", "medium", "low", "warning"];

export const SEVERITY_META: Record<Severity, SeverityMeta> = {
  high: { severity: "high", label: "高", symbol: "●", colorVar: "var(--ci-sev-high)", modifier: "high" },
  medium: { severity: "medium", label: "中", symbol: "◆", colorVar: "var(--ci-sev-medium)", modifier: "medium" },
  low: { severity: "low", label: "低", symbol: "■", colorVar: "var(--ci-sev-low)", modifier: "low" },
  warning: { severity: "warning", label: "推奨", symbol: "▲", colorVar: "var(--ci-sev-warning)", modifier: "warning" },
};

/** 画面表示ラベル(高/中/低/推奨)から Severity を引く対応表。ルールカタログの重大度欄が使う。 */
export const SEVERITY_BY_LABEL: Record<string, Severity> = {
  高: "high",
  中: "medium",
  低: "low",
  推奨: "warning",
};

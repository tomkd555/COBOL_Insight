import type { ReactElement } from "react";
import { SEVERITY_META, type Severity } from "./severity";

export interface SeverityBadgeProps {
  severity: Severity;
  /** ラベル(高/中/低/警告)を記号の右に表示するか。既定は表示する。 */
  showLabel?: boolean;
  className?: string;
}

/**
 * 重大度バッジ。色 + 記号の二重符号化で重大度を表す。記号は色覚に依存せず重大度を伝え、
 * ラベルはスクリーンリーダー向けの読み上げも兼ねる。記号自体は aria-hidden とし、
 * 読み上げは aria-label「重大度: {ラベル}」へ集約する。
 */
export function SeverityBadge({ severity, showLabel = true, className }: SeverityBadgeProps): ReactElement {
  const meta = SEVERITY_META[severity];
  const classes = ["ci-severity-badge", `ci-severity-badge--${meta.modifier}`];
  if (className) classes.push(className);
  return (
    <span className={classes.join(" ")} aria-label={`重大度: ${meta.label}`}>
      <span className="ci-severity-badge__symbol" aria-hidden="true">
        {meta.symbol}
      </span>
      {showLabel ? <span className="ci-severity-badge__label">{meta.label}</span> : null}
    </span>
  );
}

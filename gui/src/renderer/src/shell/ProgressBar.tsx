import type { ReactElement } from "react";

/**
 * 実行中の 4px アニメ進捗バー。完了時点が不定のため indeterminate な進捗として扱い、
 * aria-valuenow を持たない role=progressbar とする。
 */
export function ProgressBar(): ReactElement {
  return <div className="ci-progress" role="progressbar" aria-label="解析の進捗" aria-busy="true" />;
}

import { useMemo, useState, type ReactElement, type ReactNode } from "react";
import type { SarifFinding } from "../../../../shared/engine-api";
import { SourceInspector } from "../viewer/SourceInspector";
import { sourceCodepageOf } from "../viewer/columns";
import {
  findingLinesOf,
  identificationRanges,
  type DocumentState,
  type EditorMetrics,
} from "../viewer/viewerModel";

export interface FindingsCodeProps {
  /** 表示する資産の相対パス。空文字は未選択。 */
  file: string;
  /** 強調する行(選んだ指摘の位置)。 */
  line: number | null;
  /** 本文の取得状態。読み取りは画面側が useSourceDocument で行う。 */
  document: DocumentState;
  /** 行の強調に使う、この画面の指摘の全件。 */
  findings: readonly SarifFinding[];
  /** 資産が未選択のときに出す案内。 */
  hint: string;
  /** ペイン見出し(未選択のときは案内)へ並べる操作。 */
  children?: ReactNode;
}

/**
 * 指摘一覧・SQL指摘の下段へ出す COBOL 原本。一覧で選んだ指摘の該当行を、同じ画面のまま読むための面である。
 * 逐語対訳は出さない(原本と対訳を突き合わせるのはソースビューアの役目とし、ここは原本だけを見せる)。
 *
 * 本文の取得は画面側が担い、この部品は受け取った本文を提示するだけである。
 */
export function FindingsCode({
  file,
  line,
  document,
  findings,
  hint,
  children,
}: FindingsCodeProps): ReactElement {
  const [metrics, setMetrics] = useState<EditorMetrics | null>(null);
  const text = document.status === "ready" ? document.text : "";
  // 桁はバイトで数えるため、復号に使った文字集合が必要である(DBCS 混在行で桁がずれる)。
  const codepage = document.status === "ready" ? sourceCodepageOf(document.codepage) : "UTF-8";
  const identification = useMemo(() => identificationRanges(text, codepage), [text, codepage]);
  const findingLines = useMemo(() => findingLinesOf(findings, file), [findings, file]);
  const findingCount = findingLines.reduce((total, entry) => total + entry.count, 0);

  if (file === "") {
    return (
      <div className="ci-findings-code__hint">
        <p className="ci-findings-code__hint-text">{hint}</p>
        {children}
      </div>
    );
  }

  return (
    <SourceInspector
      file={file}
      document={document}
      findingCount={findingCount}
      metrics={metrics}
      onMetrics={setMetrics}
      findings={findingLines}
      identification={identification}
      focusLine={line}
    >
      {children}
    </SourceInspector>
  );
}

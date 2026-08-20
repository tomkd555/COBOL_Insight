import { useMemo, type ReactElement } from "react";
import { columnRuler, displayWidth } from "./importModel";

export interface ImportPreviewProps {
  /** 桁を切り出した後の各行。 */
  lines: readonly string[];
}

/** 固定形式の記録長。桁定規はこれを下限の幅として引く。 */
const RECORD_LENGTH = 80;

/**
 * 取込内容のプレビュー。桁定規と行番号を添えて、保存する本文をそのまま等幅で並べる。
 * 定規があることで、標識領域(7桁目)と A 領域の始まり(8桁目)が意図した位置に来ているかを、
 * 保存する前に確かめられる。
 */
export function ImportPreview({ lines }: ImportPreviewProps): ReactElement {
  const width = useMemo(
    () => lines.reduce((max, line) => Math.max(max, displayWidth(line)), RECORD_LENGTH),
    [lines],
  );
  const ruler = useMemo(() => columnRuler(width), [width]);

  return (
    <section className="ci-import-preview" aria-label="取込内容のプレビュー">
      <div className="ci-import-preview__head">
        <h4 className="ci-import-preview__title">取込内容のプレビュー（{lines.length} 行）</h4>
        <p className="ci-import-preview__note">
          7桁目が標識領域、8〜72桁が本文、73桁目以降が識別領域である。全角文字は2桁として数える。
        </p>
      </div>
      {lines.length === 0 ? (
        <p className="ci-import-preview__blank">
          本文を貼り付けると、桁を切り出した結果をここに表示します。
        </p>
      ) : (
        <div className="ci-import-preview__body">
          <pre className="ci-import-preview__ruler" aria-hidden="true">
            {`${ruler.tens}\n${ruler.ones}`}
          </pre>
          <ol className="ci-import-preview__lines">
            {lines.map((line, index) => (
              <li key={index} className="ci-import-preview__line">
                <span className="ci-import-preview__no">{index + 1}</span>
                <pre className="ci-import-preview__text">{line}</pre>
              </li>
            ))}
          </ol>
        </div>
      )}
    </section>
  );
}

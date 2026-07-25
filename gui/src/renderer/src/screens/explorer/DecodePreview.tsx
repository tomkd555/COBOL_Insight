import type { ReactElement } from "react";
import { codepageLabel } from "./assetView";
import type { SourcePreview } from "./sourcePreview";

export interface DecodePreviewProps {
  /** プレビューの表示状態(供給源は main の readSourceText)。 */
  preview: SourcePreview;
  /** 表示する行数の上限(design は先頭5行)。 */
  maxLines?: number;
}

/**
 * デコードプレビュー。選択資産の先頭数行を等幅で示し、復号に用いたコードページを添える。
 * 復号非対応(EBCDIC・コードページ不明)と読取失敗は、本文が空の状態と区別して明示する
 * (design のプレビュー枠)。
 */
export function DecodePreview({ preview, maxLines = 5 }: DecodePreviewProps): ReactElement {
  const invalid = preview.status === "unsupported" || preview.status === "error";
  const boxClass = invalid ? "ci-preview__box ci-preview__box--invalid" : "ci-preview__box";
  return (
    <div className="ci-preview">
      <div className="ci-preview__head">
        <span className="ci-preview__title">デコードプレビュー</span>
        <span className="ci-preview__hint">
          {preview.status === "ready" ? `先頭 ${maxLines} 行 ・ ${preview.codepage}` : `先頭 ${maxLines} 行`}
        </span>
      </div>
      <div className={boxClass} role="group" aria-label="デコードプレビュー">
        <PreviewBody preview={preview} maxLines={maxLines} />
      </div>
      <PreviewNotice preview={preview} />
    </div>
  );
}

function PreviewBody({ preview, maxLines }: { preview: SourcePreview; maxLines: number }): ReactElement {
  if (preview.status === "loading") {
    return <div className="ci-preview__empty">読み込み中…</div>;
  }
  const lines = preview.status === "ready" ? preview.lines.slice(0, maxLines) : [];
  if (lines.length === 0) {
    return <div className="ci-preview__empty">プレビューする内容がありません</div>;
  }
  return (
    <>
      {lines.map((line, index) => (
        <div key={index} className="ci-preview__line">
          {line}
        </div>
      ))}
    </>
  );
}

function PreviewNotice({ preview }: { preview: SourcePreview }): ReactElement | null {
  if (preview.status === "unsupported") {
    // 復号非対応のとき main は要求した engine の charset 名を返すため、表記へ写して示す(裁定 A8)。
    return (
      <div className="ci-preview__warn" role="alert">
        コードページ {codepageLabel(preview.codepage)} は本文の表示に対応していません。文字コードを指定し直すと表示できる場合があります。
      </div>
    );
  }
  if (preview.status === "error") {
    return (
      <div className="ci-preview__warn" role="alert">
        本文を読み取れませんでした。{preview.message}
      </div>
    );
  }
  return null;
}

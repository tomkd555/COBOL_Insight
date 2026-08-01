import type { ReactElement } from "react";
import { codepageLabel } from "./assetView";
import type { SourcePreview } from "./sourcePreview";

export interface DecodePreviewProps {
  /** プレビューの表示状態(供給源は main の readSourceText)。 */
  preview: SourcePreview;
}

/**
 * デコードプレビュー。選択資産の本文を等幅で示し、復号に用いたコードページを添える。行数は
 * 固定せず、詳細ペインの残り高さいっぱいに描いて内側でスクロールする(先頭 N 行で打ち切ると、
 * N+1 行目以降を利用者から見て理由の分からないまま取りこぼすため)。復号非対応
 * (EBCDIC・コードページ不明)と読取失敗は、本文が空の状態と区別して明示する。
 */
export function DecodePreview({ preview }: DecodePreviewProps): ReactElement {
  const invalid = preview.status === "unsupported" || preview.status === "error";
  const boxClass = invalid ? "ci-preview__box ci-preview__box--invalid" : "ci-preview__box";
  return (
    <div className="ci-preview">
      <div className="ci-preview__head">
        <h4 className="ci-preview__title">デコードプレビュー</h4>
        {preview.status === "ready" ? (
          <span className="ci-preview__hint">{preview.codepage}</span>
        ) : null}
      </div>
      <div className={boxClass} role="group" aria-label="デコードプレビュー">
        <PreviewBody preview={preview} />
      </div>
      <PreviewNotice preview={preview} />
    </div>
  );
}

function PreviewBody({ preview }: { preview: SourcePreview }): ReactElement {
  if (preview.status === "loading") {
    return <div className="ci-preview__empty">読み込み中…</div>;
  }
  const lines = preview.status === "ready" ? preview.lines : [];
  if (lines.length === 0) {
    return <div className="ci-preview__empty">プレビューする内容がない</div>;
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
    // 復号非対応のとき main は要求した engine の charset 名を返すため、利用者向けの表記へ言い換える。
    return (
      <div className="ci-preview__warn" role="alert">
        コードページ {codepageLabel(preview.codepage)} は本文の表示に対応していない。文字コードを指定し直すと表示できる場合がある。
      </div>
    );
  }
  if (preview.status === "error") {
    return (
      <div className="ci-preview__warn" role="alert">
        本文を読み取れなかった。{preview.message}
      </div>
    );
  }
  return null;
}

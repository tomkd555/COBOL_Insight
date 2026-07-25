import type { ReactElement } from "react";
import { Button } from "../../components/Button";
import type { CopyStatement } from "./copybookLookup";

/** コピー句本文の取得状態。 */
export type CopybookBody =
  | { readonly status: "loading" }
  | { readonly status: "ready"; readonly path: string; readonly text: string; readonly codepage: string }
  | { readonly status: "unsupported"; readonly path: string; readonly codepage: string }
  | { readonly status: "missing"; readonly tried: readonly string[] };

/** COPY 文1件と、その取り込み先の本文。 */
export interface CopybookEntry {
  readonly statement: CopyStatement;
  readonly body: CopybookBody;
}

export interface CopybookExpansionProps {
  /** ソースから検出した COPY 文。 */
  statements: readonly CopyStatement[];
  /** 展開中か。閉じているときは本文を読まない。 */
  open: boolean;
  onToggle: () => void;
  /** 展開中に読み込んだ本文。閉じているときは空。 */
  entries: readonly CopybookEntry[];
}

/** 本文の1件を描く。 */
function bodyView(body: CopybookBody): ReactElement {
  if (body.status === "loading") {
    return <p className="ci-viewer__copy-note">コピー句の本文を読み込んでいる…</p>;
  }
  if (body.status === "missing") {
    return (
      <p className="ci-viewer__copy-note ci-viewer__copy-note--warn">
        {body.tried.length === 0
          ? "コピー句の探索先が無い。資産一覧に同名のコピー句が無く、コピー句検索パスも未設定である。設定でコピー句検索パスを追加する。"
          : `コピー句が見つからない。探索した場所: ${body.tried.join(" / ")}。設定でコピー句検索パスを追加する。`}
      </p>
    );
  }
  if (body.status === "unsupported") {
    return (
      <p className="ci-viewer__copy-note ci-viewer__copy-note--warn">
        {`${body.path} は ${body.codepage} のため表示できない（EBCDIC とコードページ不明は復号非対応）。`}
      </p>
    );
  }
  return (
    <>
      <p className="ci-viewer__copy-source">{`${body.path}（${body.codepage}）`}</p>
      <pre className="ci-viewer__copy-body">{body.text}</pre>
    </>
  );
}

/**
 * コピー句の展開表示。COPY 文の行と、取り込まれるコピー句の本文を折り畳みで示す。
 * REPLACING の適用や入れ子の解決は engine の解析が担うため、ここでは取り込み元の本文をそのまま
 * 並べ、REPLACING 指定は原文として添える(展開後の姿を装わない)。
 */
export function CopybookExpansion({
  statements,
  open,
  onToggle,
  entries,
}: CopybookExpansionProps): ReactElement {
  return (
    <section className="ci-viewer__copy" aria-label="コピー句の展開">
      <div className="ci-viewer__copy-head">
        <span className="ci-viewer__copy-count">{`COPY 文 ${statements.length} 件`}</span>
        {statements.length === 0 ? null : (
          <Button className="ci-viewer__copy-toggle" onClick={onToggle} aria-expanded={open}>
            {open ? "− 折りたたみ" : "＋ 展開表示"}
          </Button>
        )}
      </div>
      {statements.length === 0 ? (
        <p className="ci-viewer__copy-note">このソースに COPY 文はない。</p>
      ) : !open ? (
        <ul className="ci-viewer__copy-list">
          {statements.map((statement) => (
            <li key={`${statement.line}-${statement.name}`} className="ci-viewer__copy-item">
              {`${statement.line} 行: ${statement.text}`}
            </li>
          ))}
        </ul>
      ) : (
        <ul className="ci-viewer__copy-list">
          {entries.map((entry) => (
            <li
              key={`${entry.statement.line}-${entry.statement.name}`}
              className="ci-viewer__copy-item"
            >
              <p className="ci-viewer__copy-statement">
                {`${entry.statement.line} 行: COPY ${entry.statement.name}`}
              </p>
              {entry.statement.replacing === null ? null : (
                <p className="ci-viewer__copy-replacing">
                  {`REPLACING ${entry.statement.replacing}（原文のまま表示し、置換後の姿は示さない）`}
                </p>
              )}
              {bodyView(entry.body)}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

import type { ReactElement, ReactNode } from "react";
import { EmptyState } from "../../components/EmptyState";
import { CodePane } from "./CodePane";
import { COBOL_LANGUAGE_ID } from "./cobolMonarch";
import {
  COBOL_RULERS,
  COLUMN_MARKS,
  columnLeftPx,
  type DocumentState,
  type EditorMetrics,
  type ExpansionZone,
  type FindingLine,
  type LineColumnRange,
} from "./viewerModel";

/** 未指定のときに使う空の行集合。参照を固定して CodePane の依存を安定させる。 */
const NO_LINES: readonly number[] = [];

export interface SourceInspectorProps {
  /** 表示中の資産の相対パス。 */
  file: string;
  /** 本文の取得状態。読取失敗・復号非対応は空の表示に潰さず、それぞれの理由を示す。 */
  document: DocumentState;
  /** 見出しへ出すこの資産の指摘件数。 */
  findingCount: number;
  /** 桁見出しの位置合わせに使う Monaco の実測寸法。測る前は null。 */
  metrics: EditorMetrics | null;
  onMetrics: (metrics: EditorMetrics) => void;
  /** 指摘のある行。重大度の記号と色で示す。 */
  findings: readonly FindingLine[];
  /** 識別欄(73〜80桁)の範囲。 */
  identification: readonly LineColumnRange[];
  /** ジャンプ先の行。 */
  focusLine: number | null;
  /** 中央へスクロールするだけの行。 */
  revealLine?: number | null;
  /** 相互ハイライトで強調する行。 */
  linkedLines?: readonly number[];
  /** 直訳不能の注記を持つ行。 */
  notedLines?: readonly number[];
  /** COPY 文の位置へ差し込む展開。 */
  expansions?: readonly ExpansionZone[];
  /** カーソル行が変わったときに呼ぶ。 */
  onCursorLine?: (line: number) => void;
  /** ペイン見出しの右端へ添える状態文言。 */
  status?: string | null;
  /** ペイン見出しへ並べる操作(コピー句の展開・コードの最大化など)。 */
  children?: ReactNode;
}

/**
 * COBOL 原本のペイン。ペイン見出し・固定形式の桁定規・コード面の3段を組む提示専用の部品であり、
 * 本文の取得も指摘の割り当ても行わない(いずれも呼び出し側が済ませて渡す)。
 *
 * ソースビューア・指摘一覧・SQL指摘の3画面が同じ形で原本を出すため、構造をここ1箇所に持つ。
 */
export function SourceInspector({
  file,
  document,
  findingCount,
  metrics,
  onMetrics,
  findings,
  identification,
  focusLine,
  revealLine = null,
  linkedLines = NO_LINES,
  notedLines = NO_LINES,
  expansions,
  onCursorLine,
  status = null,
  children,
}: SourceInspectorProps): ReactElement {
  const codepage =
    document.status === "ready" || document.status === "unsupported" ? document.codepage : "―";

  return (
    <section className="ci-viewer__pane" aria-label="COBOL ソース">
      <header className="ci-viewer__pane-head">
        <h3 className="ci-viewer__pane-title">{`COBOL ソース（固定形式 80 桁）― ${file}`}</h3>
        <span className="ci-viewer__pane-meta">
          {`この資産の指摘: ${findingCount} 件 ｜ 文字コード: ${codepage}`}
        </span>
        {children}
        <span className="ci-viewer__pane-spacer" />
        {status === null ? null : (
          <span className="ci-viewer__pane-meta ci-viewer__pane-status" role="status">
            {status}
          </span>
        )}
      </header>
      {/* 見出しの位置は Monaco の実測寸法から求める。測る前(本文の表示前)はラベルを出さない。 */}
      <div
        className="ci-viewer__columns"
        role="img"
        aria-label="固定形式の欄割り ― 1〜6桁 一連番号欄、7桁目 標識欄、8〜72桁 本体（A/B 領域）、73〜80桁 識別欄"
      >
        {metrics === null
          ? null
          : COLUMN_MARKS.map((mark) => (
              <span
                key={mark.name}
                className={`ci-viewer__column ci-viewer__column--${mark.name} ci-viewer__column--${mark.anchor}`}
                style={{ left: `${columnLeftPx(metrics, mark.column)}px` }}
              >
                {mark.label}
              </span>
            ))}
      </div>
      <div className="ci-viewer__pane-body">
        {document.status === "loading" ? (
          <p className="ci-viewer__loading" role="status">
            ソース本文を読み込んでいる…
          </p>
        ) : document.status === "unsupported" ? (
          <EmptyState
            icon="！"
            title="このコードページは表示できません"
            description={`${document.codepage} は表示用の復号に対応していない（EBCDIC CP930/CP939 とコードページ不明）。資産一覧で文字コードを手動指定すると表示できる場合がある。`}
          />
        ) : document.status === "error" ? (
          <EmptyState icon="！" title="ソースを読み取れませんでした" description={document.message} />
        ) : document.status === "ready" ? (
          <CodePane
            languageId={COBOL_LANGUAGE_ID}
            text={document.text}
            rulers={COBOL_RULERS}
            linkedLines={linkedLines}
            notedLines={notedLines}
            focusLine={focusLine}
            revealLine={revealLine}
            identification={identification}
            findings={findings}
            expansions={expansions}
            glyphMargin
            onMetrics={onMetrics}
            onCursorLine={onCursorLine ?? (() => undefined)}
            ariaLabel={`COBOL 原本 ${file}`}
          />
        ) : null}
      </div>
    </section>
  );
}

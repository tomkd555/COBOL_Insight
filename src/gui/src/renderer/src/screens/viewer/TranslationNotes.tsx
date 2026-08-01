import type { ReactElement } from "react";
import { mappingKindLabel, type LineRange, type TranslationNote } from "./lineMapIndex";

export interface TranslationNotesProps {
  notes: readonly TranslationNote[];
  /** 注記の行へ相互ハイライトを移す。 */
  onSelect: (note: TranslationNote) => void;
}

/** 行範囲の表示。単一行は行番号だけを示す。 */
function rangeText(range: LineRange): string {
  return range.start === range.end ? `${range.start} 行` : `${range.start}〜${range.end} 行`;
}

/**
 * 直訳できなかった箇所の一覧。engine が LINE_MAP.note へ記録した注記を、対応する COBOL 行と
 * 生成行の範囲とともに対訳ペインの下部へ並べる。逐語対訳が原文と等価でない箇所を隠さないための
 * 提示である。
 *
 * 既定では畳んでおく。開いたままではコード面の高さを常に削り、0 件でも見出しと空文言で場所を取る。
 * 0 件のときは開く先が無いので見出しだけを置く。
 */
export function TranslationNotes({ notes, onSelect }: TranslationNotesProps): ReactElement {
  if (notes.length === 0) {
    return (
      <section className="ci-viewer__notes ci-viewer__notes--empty" aria-label="直訳不能の注記">
        <p className="ci-viewer__notes-title">直訳不能の注記 0 件</p>
      </section>
    );
  }
  return (
    <section className="ci-viewer__notes" aria-label="直訳不能の注記">
      <details className="ci-viewer__notes-box">
        <summary className="ci-viewer__notes-summary">
          <h4 className="ci-viewer__notes-title">{`直訳不能の注記 ${notes.length} 件`}</h4>
        </summary>
        <ul className="ci-viewer__notes-list">
          {notes.map((note) => (
            <li key={`${note.generated.start}-${note.cobol.start}`} className="ci-viewer__note">
              <button
                type="button"
                className="ci-viewer__note-jump"
                onClick={() => onSelect(note)}
              >
                {`COBOL ${rangeText(note.cobol)} → 生成 ${rangeText(note.generated)}`}
              </button>
              <span className="ci-viewer__note-kind">{mappingKindLabel(note.kind)}</span>
              <span className="ci-viewer__note-text">{note.note}</span>
            </li>
          ))}
        </ul>
      </details>
    </section>
  );
}

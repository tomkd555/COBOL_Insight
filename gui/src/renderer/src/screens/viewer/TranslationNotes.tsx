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
 * 生成行の範囲とともに下部へ並べる。逐語対訳が原文と等価でない箇所を隠さないための提示である。
 */
export function TranslationNotes({ notes, onSelect }: TranslationNotesProps): ReactElement {
  return (
    <section className="ci-viewer__notes" aria-label="直訳不能の注記">
      <h3 className="ci-viewer__notes-title">{`直訳不能の注記 ${notes.length} 件`}</h3>
      {notes.length === 0 ? (
        <p className="ci-viewer__notes-empty">この生成物には直訳できなかった箇所の注記はない。</p>
      ) : (
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
      )}
    </section>
  );
}

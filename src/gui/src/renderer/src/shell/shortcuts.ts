/**
 * 画面全体で受けるキー操作の判定。判定だけを純関数として置き、状態の変更は呼び出し側が行う。
 */

/** キー操作の判定に要る項目。DOM の KeyboardEvent がそのまま満たす。 */
export interface ShortcutEvent {
  readonly key: string;
  readonly ctrlKey: boolean;
  readonly metaKey: boolean;
  readonly altKey: boolean;
  readonly target: EventTarget | null;
}

/** 文字を打ち込む要素。ここへ焦点があるときは画面全体のキー操作を働かせない。 */
const TEXT_ENTRY_TAGS = ["INPUT", "TEXTAREA", "SELECT"];

/** コードエディタ(Monaco)の根の目印。 */
const CODE_EDITOR_SELECTOR = ".monaco-editor";

/**
 * コードの最大化(Ctrl+B)か。検索欄へ文字を打っている間に横取りしないよう、入力欄・
 * 複数行入力・選択欄へ焦点があるときは働かせない。
 */
export function isCodeFocusShortcut(event: ShortcutEvent): boolean {
  if (!event.ctrlKey || event.metaKey || event.altKey || event.key.toLowerCase() !== "b") {
    return false;
  }
  return !isTextEntry(event.target);
}

/**
 * 利用者が文字を打ち込んでいる欄か。
 *
 * コードエディタの中の複数行入力だけは除く。Monaco は入力を隠しの textarea で受けるため、コードを
 * 読んでいるあいだは常にそこへ焦点がある。複数行入力を一律に外すと、この操作が最も要る場面
 * (コード面を見ている最中)で働かなくなる。隠しの textarea を見分ける手掛かりは Monaco が根へ付ける
 * クラスしか無いため、判定はその内部構造に依存する。
 */
function isTextEntry(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement) || !TEXT_ENTRY_TAGS.includes(target.tagName)) {
    return false;
  }
  return target.closest(CODE_EDITOR_SELECTOR) === null;
}

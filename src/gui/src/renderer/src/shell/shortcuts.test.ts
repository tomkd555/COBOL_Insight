import { describe, it, expect } from "vitest";
import { isCodeFocusShortcut, type ShortcutEvent } from "./shortcuts";

function event(patch: Partial<ShortcutEvent> = {}): ShortcutEvent {
  return { key: "b", ctrlKey: true, metaKey: false, altKey: false, target: null, ...patch };
}

/** 指定したタグの要素を作る(target の判定に使う)。 */
function element(tagName: string): HTMLElement {
  return document.createElement(tagName);
}

describe("isCodeFocusShortcut(コードの最大化)", () => {
  it("Ctrl+B で働く", () => {
    expect(isCodeFocusShortcut(event())).toBe(true);
  });

  it("大文字の B(Shift 併用)でも働く", () => {
    expect(isCodeFocusShortcut(event({ key: "B" }))).toBe(true);
  });

  it("Ctrl を伴わない B では働かない", () => {
    expect(isCodeFocusShortcut(event({ ctrlKey: false }))).toBe(false);
  });

  it("他のキーでは働かない", () => {
    expect(isCodeFocusShortcut(event({ key: "a" }))).toBe(false);
  });

  it("Alt・Meta を併せて押しているときは働かない(別の操作と取り違えない)", () => {
    expect(isCodeFocusShortcut(event({ altKey: true }))).toBe(false);
    expect(isCodeFocusShortcut(event({ metaKey: true }))).toBe(false);
  });

  it("入力欄・複数行入力・選択欄へ焦点があるときは働かない", () => {
    for (const tagName of ["input", "textarea", "select"]) {
      expect(isCodeFocusShortcut(event({ target: element(tagName) }))).toBe(false);
    }
  });

  it("入力欄でない要素へ焦点があるときは働く", () => {
    expect(isCodeFocusShortcut(event({ target: element("button") }))).toBe(true);
    expect(isCodeFocusShortcut(event({ target: element("div") }))).toBe(true);
  });

  it("コードエディタの中の複数行入力では働く(Monaco の隠し入力に焦点があってもコードを最大化できる)", () => {
    const editor = element("div");
    editor.className = "monaco-editor";
    const hidden = element("textarea");
    editor.append(hidden);
    expect(isCodeFocusShortcut(event({ target: hidden }))).toBe(true);
  });

  it("コードエディタの外の複数行入力では働かない(貼り付け欄へ打っている間は横取りしない)", () => {
    const form = element("div");
    const paste = element("textarea");
    form.append(paste);
    expect(isCodeFocusShortcut(event({ target: paste }))).toBe(false);
  });
});

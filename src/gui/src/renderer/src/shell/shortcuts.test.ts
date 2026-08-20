import { describe, it, expect } from "vitest";
import { shortcutOf, type ShortcutEvent } from "./shortcuts";

function event(patch: Partial<ShortcutEvent> = {}): ShortcutEvent {
  return {
    key: "b",
    ctrlKey: true,
    metaKey: false,
    altKey: false,
    shiftKey: false,
    target: null,
    ...patch,
  };
}

/** 指定したタグの要素を作る(target の判定に使う)。 */
function element(tagName: string): HTMLElement {
  return document.createElement(tagName);
}

describe("shortcutOf(画面全体のキー操作)", () => {
  it("パネルの開閉とタブの操作を割り当てる", () => {
    expect(shortcutOf(event({ key: "b" }))).toBe("toggleSide");
    expect(shortcutOf(event({ key: "B" }))).toBe("toggleSide");
    expect(shortcutOf(event({ key: "j" }))).toBe("toggleBottom");
    expect(shortcutOf(event({ key: "w" }))).toBe("closeTab");
    expect(shortcutOf(event({ key: "PageDown" }))).toBe("nextTab");
    expect(shortcutOf(event({ key: "PageUp" }))).toBe("previousTab");
  });

  it("Ctrl を伴わないキーには応じない", () => {
    expect(shortcutOf(event({ ctrlKey: false }))).toBeNull();
  });

  it("割り当ての無いキーには応じない", () => {
    expect(shortcutOf(event({ key: "a" }))).toBeNull();
  });

  it("Alt・Meta を併せて押しているときは応じない(別の操作と取り違えない)", () => {
    expect(shortcutOf(event({ altKey: true }))).toBeNull();
    expect(shortcutOf(event({ metaKey: true }))).toBeNull();
  });

  it("入力欄・複数行入力・選択欄へ焦点があるときは応じない", () => {
    for (const tagName of ["input", "textarea", "select"]) {
      expect(shortcutOf(event({ target: element(tagName) }))).toBeNull();
    }
  });

  it("入力欄でない要素へ焦点があるときは応じる", () => {
    expect(shortcutOf(event({ target: element("button") }))).toBe("toggleSide");
    expect(shortcutOf(event({ target: element("div") }))).toBe("toggleSide");
  });

  it("コードエディタの中の複数行入力では応じる(Monaco の隠し入力に焦点があっても効かせる)", () => {
    const editor = element("div");
    editor.className = "monaco-editor";
    const hidden = element("textarea");
    editor.append(hidden);
    expect(shortcutOf(event({ target: hidden }))).toBe("toggleSide");
  });

  it("コードエディタの外の複数行入力では応じない(貼り付け欄へ打っている間は横取りしない)", () => {
    const form = element("div");
    const paste = element("textarea");
    form.append(paste);
    expect(shortcutOf(event({ target: paste }))).toBeNull();
  });
});

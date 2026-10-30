import { beforeEach, describe, expect, it, vi } from "vitest";
import { render } from "@testing-library/react";
import { SideBySide } from "./SideBySide";

/**
 * Two editors that keep what the pair registers on them. Like Monaco's, `setPosition` and
 * `setScrollTop` raise the cursor and scroll events synchronously, which is how an echo arises.
 */
const fake = vi.hoisted(() => {
  type Listener<T> = (event: T) => void;
  class FakeEditor {
    cursor: Listener<{ position: { lineNumber: number; column: number } }>[] = [];
    scroll: Listener<{ scrollTopChanged: boolean }>[] = [];
    firstVisibleLine = 1;
    model: { dispose(): void } | null = null;

    setPosition = vi.fn((position: { lineNumber: number; column: number }) => {
      for (const listener of this.cursor) {
        listener({ position });
      }
    });
    revealLineInCenter = vi.fn();
    /** The text scrolls to `top`, so the first visible line becomes the one that sits there. */
    setScrollTop = vi.fn((top: number) => {
      this.firstVisibleLine = top / 100;
      for (const listener of this.scroll) {
        listener({ scrollTopChanged: true });
      }
    });
    /** A line sits 100 pixels from the top per line number, so a target can be told from its line. */
    getTopForLineNumber = (line: number): number => line * 100;
    getVisibleRanges = (): { startLineNumber: number }[] => [{ startLineNumber: this.firstVisibleLine }];
    onDidChangeCursorPosition = (listener: Listener<{ position: { lineNumber: number; column: number } }>) => {
      this.cursor.push(listener);
      return { dispose: (): void => undefined };
    };
    onDidScrollChange = (listener: Listener<{ scrollTopChanged: boolean }>) => {
      this.scroll.push(listener);
      return { dispose: (): void => undefined };
    };
    getModel = (): { dispose(): void } | null => this.model;
    setModel = (model: { dispose(): void } | null): void => {
      this.model = model;
    };
    updateOptions = vi.fn();
    dispose = vi.fn();

    /** What the reader does: the cursor moves to a line, the text scrolls to a first line. */
    moveCursorTo(lineNumber: number): void {
      for (const listener of this.cursor) {
        listener({ position: { lineNumber, column: 5 } });
      }
    }
    scrollTo(firstLine: number, scrollTopChanged = true): void {
      this.firstVisibleLine = firstLine;
      for (const listener of this.scroll) {
        listener({ scrollTopChanged });
      }
    }
  }
  return { FakeEditor, editors: [] as InstanceType<typeof FakeEditor>[] };
});

vi.mock("../../vendor/monacoEditor", () => ({
  monacoEditor: () => ({
    editor: {
      create: () => {
        const editor = new fake.FakeEditor();
        fake.editors.push(editor);
        return editor;
      },
      createModel: () => ({ dispose: (): void => undefined }),
    },
  }),
}));
vi.mock("../../vendor/monacoLanguages", () => ({ registerLanguages: vi.fn() }));

/** The left pane's lines 3 and 4 correspond to 10 and 20 on the right, and nothing else corresponds. */
const TO_RIGHT = (line: number): number | null => ({ 3: 10, 4: 20 })[line as 3 | 4] ?? null;
const TO_LEFT = (line: number): number | null => ({ 10: 3, 20: 4 })[line as 10 | 20] ?? null;

function mount(): { left: InstanceType<typeof fake.FakeEditor>; right: InstanceType<typeof fake.FakeEditor> } {
  render(
    <SideBySide
      left="COBOL"
      leftLanguageId="cobol"
      leftLabel="cobol/A.cbl"
      right="print()"
      rightLanguageId="python"
      rightLabel="A.py"
      toRight={TO_RIGHT}
      toLeft={TO_LEFT}
    />,
  );
  const [left, right] = fake.editors.slice(-2);
  return { left, right };
}

beforeEach(() => {
  fake.editors.length = 0;
});

describe("SideBySide", () => {
  it("moves the right editor to the mapped line when the cursor moves in the left, and ignores the echo", () => {
    const { left, right } = mount();

    left.moveCursorTo(3);

    expect(right.setPosition).toHaveBeenCalledTimes(1);
    expect(right.setPosition).toHaveBeenCalledWith({ lineNumber: 10, column: 1 });
    expect(right.revealLineInCenter).toHaveBeenCalledWith(10, 1);
    // The right editor raised its own cursor event from inside setPosition; the left was not moved back.
    expect(left.setPosition).not.toHaveBeenCalled();
  });

  it("moves the left editor to the mapped line when the cursor moves in the right, and ignores the echo", () => {
    const { left, right } = mount();

    right.moveCursorTo(20);

    expect(left.setPosition).toHaveBeenCalledTimes(1);
    expect(left.setPosition).toHaveBeenCalledWith({ lineNumber: 4, column: 1 });
    expect(left.revealLineInCenter).toHaveBeenCalledWith(4, 1);
    expect(right.setPosition).not.toHaveBeenCalled();
  });

  it("scrolls the other editor to the mapped line when the top of the text moved", () => {
    const { left, right } = mount();

    left.scrollTo(4);

    expect(right.setScrollTop).toHaveBeenCalledTimes(1);
    expect(right.setScrollTop).toHaveBeenCalledWith(2000);
    expect(left.setScrollTop).not.toHaveBeenCalled();
  });

  it("scrolls the left editor to the mapped line when the right one scrolled", () => {
    const { left, right } = mount();

    right.scrollTo(10);

    expect(left.setScrollTop).toHaveBeenCalledWith(300);
    expect(right.setScrollTop).not.toHaveBeenCalled();
  });

  it("moves nothing on a scroll event that reports no change of the top", () => {
    const { left, right } = mount();

    left.scrollTo(4, false);
    right.scrollTo(20, false);

    expect(left.setScrollTop).not.toHaveBeenCalled();
    expect(right.setScrollTop).not.toHaveBeenCalled();
  });

  it("moves nothing for a line the map has no row for", () => {
    const { left, right } = mount();

    left.moveCursorTo(7);
    left.scrollTo(7);
    right.moveCursorTo(15);
    right.scrollTo(15);

    expect(left.setPosition).not.toHaveBeenCalled();
    expect(right.setPosition).not.toHaveBeenCalled();
    expect(left.setScrollTop).not.toHaveBeenCalled();
    expect(right.setScrollTop).not.toHaveBeenCalled();
  });
});

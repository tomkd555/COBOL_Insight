import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { fireEvent } from "@testing-library/dom";
import { SplitHandle, maxSplitSize, nextSplitSize } from "./SplitHandle";

describe("nextSplitSize", () => {
  it("narrows the pane after the handle as the handle moves right", () => {
    expect(nextSplitSize(300, 40, "after", 100, 600)).toBe(260);
  });

  it("widens the pane before the handle as the handle moves right", () => {
    expect(nextSplitSize(300, 40, "before", 100, 600)).toBe(340);
  });

  it("clamps to the minimum and the maximum", () => {
    expect(nextSplitSize(300, 500, "after", 100, 600)).toBe(100);
    expect(nextSplitSize(300, -500, "after", 100, 600)).toBe(600);
  });

  it("rounds fractional movement", () => {
    expect(nextSplitSize(300, 0.4, "after", 100, 600)).toBe(300);
  });
});

describe("maxSplitSize", () => {
  it("leaves the other pane its minimum and the handle its width", () => {
    expect(maxSplitSize(1000, 6, 200, 520)).toBe(474);
  });

  it("never falls below this pane's own minimum", () => {
    expect(maxSplitSize(300, 6, 200, 520)).toBe(200);
  });

  it("imposes no maximum while the container cannot be measured", () => {
    // Treating an unmeasurable container as 0 would clamp the pane down to its minimum.
    expect(maxSplitSize(0, 6, 200, 520)).toBe(Number.POSITIVE_INFINITY);
  });
});

describe("SplitHandle", () => {
  it("exposes itself as a separator with its current and minimum sizes", () => {
    render(
      <SplitHandle size={280} min={200} oppositeMin={520} onSizeChange={vi.fn()} ariaLabel="幅" />,
    );
    const separator = screen.getByRole("separator", { name: "幅" });
    expect(separator).toHaveAttribute("aria-valuenow", "280");
    expect(separator).toHaveAttribute("aria-valuemin", "200");
    expect(separator).toHaveAttribute("aria-orientation", "vertical");
  });

  it("resizes with the arrow keys and commits once on release", () => {
    const onSizeChange = vi.fn();
    const onCommit = vi.fn();
    render(
      <SplitHandle
        size={280}
        min={200}
        oppositeMin={520}
        step={20}
        onSizeChange={onSizeChange}
        onCommit={onCommit}
        ariaLabel="幅"
      />,
    );
    const separator = screen.getByRole("separator", { name: "幅" });
    fireEvent.keyDown(separator, { key: "ArrowLeft" });
    fireEvent.keyDown(separator, { key: "ArrowLeft" });
    fireEvent.keyUp(separator, { key: "ArrowLeft" });
    expect(onSizeChange).toHaveBeenCalledTimes(2);
    expect(onSizeChange).toHaveBeenLastCalledWith(300);
    // A key repeat is one gesture, so it saves once.
    expect(onCommit).toHaveBeenCalledTimes(1);
  });

  it("goes to the minimum on Home", () => {
    const onSizeChange = vi.fn();
    render(
      <SplitHandle size={280} min={200} oppositeMin={520} onSizeChange={onSizeChange} ariaLabel="幅" />,
    );
    fireEvent.keyDown(screen.getByRole("separator", { name: "幅" }), { key: "Home" });
    expect(onSizeChange).toHaveBeenCalledWith(200);
  });

  it("ignores a key it does not handle, and commits nothing", () => {
    const onSizeChange = vi.fn();
    const onCommit = vi.fn();
    render(
      <SplitHandle
        size={280}
        min={200}
        oppositeMin={520}
        onSizeChange={onSizeChange}
        onCommit={onCommit}
        ariaLabel="幅"
      />,
    );
    const separator = screen.getByRole("separator", { name: "幅" });
    fireEvent.keyDown(separator, { key: "a" });
    fireEvent.keyUp(separator, { key: "a" });
    expect(onSizeChange).not.toHaveBeenCalled();
    expect(onCommit).not.toHaveBeenCalled();
  });

  it("goes back to the size that was asked for once the container has room again", () => {
    // A page zoom shrinks the viewport. The pane is rendered at the size that fits, but the size
    // asked for is kept, so zooming back to 100% does not leave the pane narrow for good.
    const onSizeChange = vi.fn();
    const { rerender } = render(
      <SplitHandle size={400} min={200} oppositeMin={520} onSizeChange={onSizeChange} ariaLabel="幅" />,
    );
    const separator = screen.getByRole("separator", { name: "幅" });
    const container = separator.parentElement as HTMLElement;
    let containerWidth = 1000;
    separator.getBoundingClientRect = () => ({ width: 6, height: 0 }) as DOMRect;
    container.getBoundingClientRect = () => ({ width: containerWidth, height: 0 }) as DOMRect;

    // 1000 px leaves room for 474, so 400 stands.
    fireEvent(window, new Event("resize"));
    expect(onSizeChange).not.toHaveBeenCalled();

    // 800 px leaves room for 274 only, and the caller renders that.
    containerWidth = 800;
    fireEvent(window, new Event("resize"));
    expect(onSizeChange).toHaveBeenLastCalledWith(274);
    rerender(
      <SplitHandle size={274} min={200} oppositeMin={520} onSizeChange={onSizeChange} ariaLabel="幅" />,
    );

    containerWidth = 1000;
    fireEvent(window, new Event("resize"));
    expect(onSizeChange).toHaveBeenLastCalledWith(400);
  });

  it("uses the vertical arrows and reports horizontal orientation when stacked", () => {
    const onSizeChange = vi.fn();
    render(
      <SplitHandle
        size={240}
        min={120}
        oppositeMin={200}
        step={20}
        orientation="horizontal"
        onSizeChange={onSizeChange}
        ariaLabel="高さ"
      />,
    );
    const separator = screen.getByRole("separator", { name: "高さ" });
    expect(separator).toHaveAttribute("aria-orientation", "horizontal");
    fireEvent.keyDown(separator, { key: "ArrowUp" });
    expect(onSizeChange).toHaveBeenCalledWith(260);
  });
});

import { act, render, screen, fireEvent } from "@testing-library/react";
import { useState, type ReactElement } from "react";
import { describe, it, expect, vi } from "vitest";
import { SplitHandle, nextSplitWidth } from "./SplitHandle";

/** 幅の変化を実際に反映する器(aria-valuenow の追従を観測する)。 */
function Harness({ initial = 300 }: { initial?: number }): ReactElement {
  const [width, setWidth] = useState(initial);
  return (
    <SplitHandle
      width={width}
      min={200}
      max={500}
      onWidthChange={setWidth}
      ariaLabel="詳細ペインの幅"
    />
  );
}

/** ハンドルを掴み、ポインタを clientX まで動かして離す。 */
function drag(from: number, to: number): void {
  fireEvent.mouseDown(screen.getByRole("separator"), { clientX: from });
  fireEvent.mouseMove(window, { clientX: to });
  fireEvent.mouseUp(window);
}

describe("nextSplitWidth(幅の決定)", () => {
  it("ハンドルを右へ動かすと、右側のペインは動かした分だけ狭くなる", () => {
    expect(nextSplitWidth(300, 40, 100, 500)).toBe(260);
  });

  it("左へ動かすと広くなる", () => {
    expect(nextSplitWidth(300, -40, 100, 500)).toBe(340);
  });

  it("下限を下回らない", () => {
    expect(nextSplitWidth(300, 400, 100, 500)).toBe(100);
  });

  it("上限を超えない", () => {
    expect(nextSplitWidth(300, -400, 100, 500)).toBe(500);
  });

  it("画素の端数を丸める", () => {
    expect(nextSplitWidth(300, 0.4, 100, 500)).toBe(300);
  });
});

describe("SplitHandle(分割ハンドル)", () => {
  it("縦の区切りとして、現在値と可動範囲を ARIA へ出す", () => {
    render(<Harness />);
    const handle = screen.getByRole("separator", { name: "詳細ペインの幅" });
    expect(handle).toHaveAttribute("aria-orientation", "vertical");
    expect(handle).toHaveAttribute("aria-valuenow", "300");
    expect(handle).toHaveAttribute("aria-valuemin", "200");
    expect(handle).toHaveAttribute("aria-valuemax", "500");
    expect(handle).toHaveAttribute("tabindex", "0");
  });

  it("マウスでドラッグすると幅が変わる", () => {
    render(<Harness />);
    drag(600, 540);
    expect(screen.getByRole("separator")).toHaveAttribute("aria-valuenow", "360");
  });

  it("ドラッグ中は掴んだ時点の幅を基準に追従する", () => {
    const onWidthChange = vi.fn();
    render(
      <SplitHandle
        width={300}
        min={200}
        max={500}
        onWidthChange={onWidthChange}
        ariaLabel="詳細ペインの幅"
      />,
    );
    fireEvent.mouseDown(screen.getByRole("separator"), { clientX: 600 });
    fireEvent.mouseMove(window, { clientX: 580 });
    fireEvent.mouseMove(window, { clientX: 560 });
    expect(onWidthChange).toHaveBeenNthCalledWith(1, 320);
    expect(onWidthChange).toHaveBeenNthCalledWith(2, 340);
  });

  it("掴んだ直後の移動を取り落とさない", () => {
    const onWidthChange = vi.fn();
    render(
      <SplitHandle
        width={300}
        min={200}
        max={500}
        onWidthChange={onWidthChange}
        ariaLabel="詳細ペインの幅"
      />,
    );
    const handle = screen.getByRole("separator");
    // 掴んでから離すまでを1つの act に収め、実ブラウザと同じく効果の実行を待たずに移動を起こす。
    act(() => {
      handle.dispatchEvent(new MouseEvent("mousedown", { clientX: 600, bubbles: true }));
      window.dispatchEvent(new MouseEvent("mousemove", { clientX: 560, bubbles: true }));
      window.dispatchEvent(new MouseEvent("mouseup", { bubbles: true }));
    });
    expect(onWidthChange).toHaveBeenCalledWith(340);
  });

  it("離したあとのポインタの移動は幅を変えない", () => {
    render(<Harness />);
    drag(600, 540);
    fireEvent.mouseMove(window, { clientX: 100 });
    expect(screen.getByRole("separator")).toHaveAttribute("aria-valuenow", "360");
  });

  it("ドラッグ中であることをクラスで示す", () => {
    render(<Harness />);
    const handle = screen.getByRole("separator");
    fireEvent.mouseDown(handle, { clientX: 600 });
    expect(handle).toHaveClass("ci-split--dragging");
    fireEvent.mouseUp(window);
    expect(handle).not.toHaveClass("ci-split--dragging");
  });

  it("← → キーで一定量ずつ動かす", () => {
    render(<Harness />);
    const handle = screen.getByRole("separator");
    fireEvent.keyDown(handle, { key: "ArrowLeft" });
    expect(handle).toHaveAttribute("aria-valuenow", "324");
    fireEvent.keyDown(handle, { key: "ArrowRight" });
    expect(handle).toHaveAttribute("aria-valuenow", "300");
  });

  it("矢印キーでも下限・上限を超えない", () => {
    render(<Harness initial={205} />);
    const handle = screen.getByRole("separator");
    fireEvent.keyDown(handle, { key: "ArrowRight" });
    expect(handle).toHaveAttribute("aria-valuenow", "200");
  });

  it("Home で下限、End で上限へ飛ぶ", () => {
    render(<Harness />);
    const handle = screen.getByRole("separator");
    fireEvent.keyDown(handle, { key: "Home" });
    expect(handle).toHaveAttribute("aria-valuenow", "200");
    fireEvent.keyDown(handle, { key: "End" });
    expect(handle).toHaveAttribute("aria-valuenow", "500");
  });

  it("関係のないキーでは幅を変えない", () => {
    const onWidthChange = vi.fn();
    render(
      <SplitHandle
        width={300}
        min={200}
        max={500}
        onWidthChange={onWidthChange}
        ariaLabel="詳細ペインの幅"
      />,
    );
    fireEvent.keyDown(screen.getByRole("separator"), { key: "a" });
    expect(onWidthChange).not.toHaveBeenCalled();
  });

  it("移動量は step で変えられる", () => {
    const onWidthChange = vi.fn();
    render(
      <SplitHandle
        width={300}
        min={200}
        max={500}
        step={80}
        onWidthChange={onWidthChange}
        ariaLabel="詳細ペインの幅"
      />,
    );
    fireEvent.keyDown(screen.getByRole("separator"), { key: "ArrowLeft" });
    expect(onWidthChange).toHaveBeenCalledWith(380);
  });
});

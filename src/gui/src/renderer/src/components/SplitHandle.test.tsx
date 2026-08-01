import { act, render, screen, fireEvent } from "@testing-library/react";
import { useState, type ReactElement } from "react";
import { describe, it, expect, vi } from "vitest";
import {
  SplitHandle,
  maxSplitSize,
  nextSplitSize,
  nextSplitWidth,
  type SplitHandleProps,
  type SplitOrientation,
  type SplitSide,
} from "./SplitHandle";

/** 器の寸法(jsdom はレイアウトを持たないため、コンテナの実寸は明示的に与える)。 */
interface ContainerBox {
  readonly width: number;
  readonly height: number;
}

function rect(width: number, height: number): DOMRect {
  return {
    x: 0,
    y: 0,
    width,
    height,
    top: 0,
    left: 0,
    right: width,
    bottom: height,
    toJSON: () => ({}),
  };
}

/**
 * ハンドルの親要素(コンテナ)とハンドル自身に実寸を持たせる。可動上限はこの実寸から導かれるため、
 * 上限を確かめるテストではこれを与える。与えなければ「まだ測れていない」状態を表す。
 */
function stubLayout(container: ContainerBox, handleSize = 6): void {
  const handle = screen.getByRole("separator");
  const parent = handle.parentElement;
  if (parent === null) {
    throw new Error("ハンドルの親要素が無い");
  }
  parent.getBoundingClientRect = () => rect(container.width, container.height);
  handle.getBoundingClientRect = () => rect(handleSize, handleSize);
}

/** 幅の変化を実際に反映する器(aria-valuenow の追従を観測する)。 */
function Harness({
  initial = 300,
  ...props
}: { initial?: number } & Partial<SplitHandleProps>): ReactElement {
  const [size, setSize] = useState(initial);
  return (
    <SplitHandle
      size={size}
      min={200}
      oppositeMin={400}
      onSizeChange={setSize}
      ariaLabel="詳細ペインの幅"
      {...props}
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

describe("nextSplitSize(操作する側による符号の反転)", () => {
  it("after(右・下のペイン)は nextSplitWidth と同じである", () => {
    expect(nextSplitSize(300, 40, "after", 100, 500)).toBe(nextSplitWidth(300, 40, 100, 500));
    expect(nextSplitSize(300, -40, "after", 100, 500)).toBe(nextSplitWidth(300, -40, 100, 500));
  });

  it("before(左・上のペイン)は、ハンドルを右・下へ動かすと広くなる", () => {
    expect(nextSplitSize(300, 40, "before", 100, 500)).toBe(340);
    expect(nextSplitSize(300, -40, "before", 100, 500)).toBe(260);
  });

  it("before でも下限と上限で頭打ちにする", () => {
    expect(nextSplitSize(300, -400, "before", 100, 500)).toBe(100);
    expect(nextSplitSize(300, 400, "before", 100, 500)).toBe(500);
  });
});

describe("maxSplitSize(可動上限の導出)", () => {
  it("コンテナの寸法から相手側の下限とハンドルの寸法を引いた値である", () => {
    expect(maxSplitSize(1000, 6, 200, 400)).toBe(594);
  });

  it("コンテナが縮むと上限も下がる", () => {
    expect(maxSplitSize(700, 6, 200, 400)).toBe(294);
  });

  it("相手側の下限を残せない狭さでも、このペインの下限は保つ", () => {
    expect(maxSplitSize(500, 6, 200, 400)).toBe(200);
  });

  it("コンテナを測れていない間は上限を敷かない", () => {
    expect(maxSplitSize(0, 0, 200, 400)).toBe(Number.POSITIVE_INFINITY);
  });
});

describe("SplitHandle(分割ハンドル)", () => {
  it("縦の区切りとして、現在値と下限を ARIA へ出す", () => {
    render(<Harness />);
    const handle = screen.getByRole("separator", { name: "詳細ペインの幅" });
    expect(handle).toHaveAttribute("aria-orientation", "vertical");
    expect(handle).toHaveAttribute("aria-valuenow", "300");
    expect(handle).toHaveAttribute("aria-valuemin", "200");
    expect(handle).toHaveAttribute("tabindex", "0");
  });

  it("コンテナを測れていない間は上限を示さない", () => {
    render(<Harness />);
    expect(screen.getByRole("separator")).not.toHaveAttribute("aria-valuemax");
  });

  it("上限は相手側の下限から導き、コンテナを縮めると上限も下がる", () => {
    render(<Harness />);
    stubLayout({ width: 1000, height: 800 });
    act(() => {
      window.dispatchEvent(new Event("resize"));
    });
    // 1000 - 400(相手側の下限) - 6(ハンドル) = 594
    expect(screen.getByRole("separator")).toHaveAttribute("aria-valuemax", "594");

    stubLayout({ width: 800, height: 800 });
    act(() => {
      window.dispatchEvent(new Event("resize"));
    });
    expect(screen.getByRole("separator")).toHaveAttribute("aria-valuemax", "394");
  });

  it("広げても相手側は下限を割らない", () => {
    render(<Harness />);
    stubLayout({ width: 1000, height: 800 });
    act(() => {
      window.dispatchEvent(new Event("resize"));
    });
    // 左へ大きく引いても、上限(594)で止まる。
    drag(600, 100);
    const size = Number(screen.getByRole("separator").getAttribute("aria-valuenow"));
    expect(size).toBe(594);
    expect(1000 - size - 6).toBeGreaterThanOrEqual(400);
  });

  it("コンテナが縮んだ結果、上限を超えた寸法は端で丸める", () => {
    render(<Harness initial={560} />);
    stubLayout({ width: 800, height: 800 });
    act(() => {
      window.dispatchEvent(new Event("resize"));
    });
    // 800 - 400 - 6 = 394 まで自動で縮む。
    expect(screen.getByRole("separator")).toHaveAttribute("aria-valuenow", "394");
  });

  it("マウスでドラッグすると幅が変わる", () => {
    render(<Harness />);
    drag(600, 540);
    expect(screen.getByRole("separator")).toHaveAttribute("aria-valuenow", "360");
  });

  it("ドラッグ中は掴んだ時点の幅を基準に追従する", () => {
    const onSizeChange = vi.fn();
    render(<Harness onSizeChange={onSizeChange} />);
    fireEvent.mouseDown(screen.getByRole("separator"), { clientX: 600 });
    fireEvent.mouseMove(window, { clientX: 580 });
    fireEvent.mouseMove(window, { clientX: 560 });
    expect(onSizeChange).toHaveBeenNthCalledWith(1, 320);
    expect(onSizeChange).toHaveBeenNthCalledWith(2, 340);
  });

  it("掴んだ直後の移動を取り落とさない", () => {
    const onSizeChange = vi.fn();
    render(<Harness onSizeChange={onSizeChange} />);
    const handle = screen.getByRole("separator");
    // 掴んでから離すまでを1つの act に収め、実ブラウザと同じく効果の実行を待たずに移動を起こす。
    act(() => {
      handle.dispatchEvent(new MouseEvent("mousedown", { clientX: 600, bubbles: true }));
      window.dispatchEvent(new MouseEvent("mousemove", { clientX: 560, bubbles: true }));
      window.dispatchEvent(new MouseEvent("mouseup", { bubbles: true }));
    });
    expect(onSizeChange).toHaveBeenCalledWith(340);
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

  it("矢印キーでも下限を超えない", () => {
    render(<Harness initial={205} />);
    const handle = screen.getByRole("separator");
    fireEvent.keyDown(handle, { key: "ArrowRight" });
    expect(handle).toHaveAttribute("aria-valuenow", "200");
  });

  it("Home で下限、End で導いた上限へ飛ぶ", () => {
    render(<Harness />);
    stubLayout({ width: 1000, height: 800 });
    const handle = screen.getByRole("separator");
    fireEvent.keyDown(handle, { key: "Home" });
    expect(handle).toHaveAttribute("aria-valuenow", "200");
    fireEvent.keyDown(handle, { key: "End" });
    expect(handle).toHaveAttribute("aria-valuenow", "594");
  });

  it("上限を測れていないときの End は寸法を変えない", () => {
    const onSizeChange = vi.fn();
    render(<Harness onSizeChange={onSizeChange} />);
    fireEvent.keyDown(screen.getByRole("separator"), { key: "End" });
    expect(onSizeChange).toHaveBeenCalledWith(300);
  });

  it("関係のないキーでは幅を変えない", () => {
    const onSizeChange = vi.fn();
    render(<Harness onSizeChange={onSizeChange} />);
    fireEvent.keyDown(screen.getByRole("separator"), { key: "a" });
    expect(onSizeChange).not.toHaveBeenCalled();
  });

  it("移動量は step で変えられる", () => {
    const onSizeChange = vi.fn();
    render(<Harness onSizeChange={onSizeChange} step={80} />);
    fireEvent.keyDown(screen.getByRole("separator"), { key: "ArrowLeft" });
    expect(onSizeChange).toHaveBeenCalledWith(380);
  });
});

describe("SplitHandle(横向きと前側のペイン)", () => {
  const horizontal: { orientation: SplitOrientation; side: SplitSide } = {
    orientation: "horizontal",
    side: "before",
  };

  it("横向きであることを ARIA へ出す", () => {
    render(<Harness {...horizontal} />);
    expect(screen.getByRole("separator")).toHaveAttribute("aria-orientation", "horizontal");
  });

  it("横向きは上下の位置(clientY)で寸法を決め、上側のペインは下へ動かすほど広がる", () => {
    render(<Harness {...horizontal} />);
    fireEvent.mouseDown(screen.getByRole("separator"), { clientY: 400 });
    fireEvent.mouseMove(window, { clientY: 440 });
    fireEvent.mouseUp(window);
    expect(screen.getByRole("separator")).toHaveAttribute("aria-valuenow", "340");
  });

  it("横向きは ↑ ↓ キーで動かす(← → は効かない)", () => {
    render(<Harness {...horizontal} />);
    const handle = screen.getByRole("separator");
    fireEvent.keyDown(handle, { key: "ArrowDown" });
    expect(handle).toHaveAttribute("aria-valuenow", "324");
    fireEvent.keyDown(handle, { key: "ArrowUp" });
    expect(handle).toHaveAttribute("aria-valuenow", "300");
    fireEvent.keyDown(handle, { key: "ArrowLeft" });
    expect(handle).toHaveAttribute("aria-valuenow", "300");
  });

  it("横向きの上限はコンテナの高さから導く", () => {
    render(<Harness {...horizontal} />);
    stubLayout({ width: 1000, height: 700 });
    act(() => {
      window.dispatchEvent(new Event("resize"));
    });
    expect(screen.getByRole("separator")).toHaveAttribute("aria-valuemax", "294");
  });
});

describe("SplitHandle(操作の完了の通知)", () => {
  it("ドラッグを離した時点で1回だけ呼ぶ", () => {
    const onCommit = vi.fn();
    render(<Harness onCommit={onCommit} />);
    fireEvent.mouseDown(screen.getByRole("separator"), { clientX: 600 });
    fireEvent.mouseMove(window, { clientX: 580 });
    fireEvent.mouseMove(window, { clientX: 560 });
    expect(onCommit).not.toHaveBeenCalled();
    fireEvent.mouseUp(window);
    expect(onCommit).toHaveBeenCalledTimes(1);
  });

  it("キーを離した時点で1回だけ呼ぶ(押しっぱなしの繰り返しでも1回)", () => {
    const onCommit = vi.fn();
    render(<Harness onCommit={onCommit} />);
    const handle = screen.getByRole("separator");
    fireEvent.keyDown(handle, { key: "ArrowLeft" });
    fireEvent.keyDown(handle, { key: "ArrowLeft" });
    expect(onCommit).not.toHaveBeenCalled();
    fireEvent.keyUp(handle, { key: "ArrowLeft" });
    expect(onCommit).toHaveBeenCalledTimes(1);
  });

  it("寸法を変えないキーでは呼ばない", () => {
    const onCommit = vi.fn();
    render(<Harness onCommit={onCommit} />);
    const handle = screen.getByRole("separator");
    fireEvent.keyDown(handle, { key: "a" });
    fireEvent.keyUp(handle, { key: "a" });
    expect(onCommit).not.toHaveBeenCalled();
  });
});

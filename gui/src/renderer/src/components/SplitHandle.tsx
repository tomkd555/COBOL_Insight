import { useEffect, useRef, useState, type KeyboardEvent, type MouseEvent, type ReactElement } from "react";

/** 矢印キー1回あたりの移動量(画素)の既定。 */
const DEFAULT_STEP = 24;

export interface SplitHandleProps {
  /** 操作対象のペイン(ハンドルの右側)の現在幅(画素)。 */
  width: number;
  /** 幅の下限(画素)。 */
  min: number;
  /** 幅の上限(画素)。 */
  max: number;
  onWidthChange: (width: number) => void;
  /** 矢印キー1回の移動量(画素)。 */
  step?: number;
  ariaLabel: string;
}

/**
 * ハンドルの移動量から新しいペイン幅を求める。操作対象はハンドルの右側のペインであり、
 * ハンドルを右へ動かす(移動量が正)ほど狭くなる。画素の端数は丸め、下限と上限で頭打ちにする。
 */
export function nextSplitWidth(base: number, delta: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, Math.round(base - delta)));
}

/**
 * 隣り合う2つの領域の境界に置く分割ハンドル。右側のペインの幅を、マウスのドラッグと
 * キーボード(← → で step ずつ、Home で下限、End で上限)で変える。
 *
 * 幅そのものは持たず、現在値を受け取って変更を通知するだけである。保持は呼び出し側(AppState)が担う。
 */
export function SplitHandle({
  width,
  min,
  max,
  onWidthChange,
  step = DEFAULT_STEP,
  ariaLabel,
}: SplitHandleProps): ReactElement {
  const [dragging, setDragging] = useState(false);
  const changeRef = useRef(onWidthChange);
  changeRef.current = onWidthChange;
  /** ドラッグ中の購読を外す手続き。ドラッグしていない間は null。 */
  const stopRef = useRef<(() => void) | null>(null);

  // ドラッグの途中でこの画面を離れた場合に、窓へ残った購読を外す。
  useEffect(() => () => stopRef.current?.(), []);

  /**
   * ドラッグ中はハンドルの外(隣のペインの上)へポインタが出るため、窓全体で移動と解放を受ける。
   * 購読は効果ではなくこの場で張る。効果は描画の後に走るため、掴んだ直後の移動を取り落とす。
   */
  function onMouseDown(event: MouseEvent<HTMLDivElement>): void {
    // 既定の選択開始を止め、ドラッグが隣のペインの本文を選ばないようにする。
    event.preventDefault();
    const origin = { clientX: event.clientX, width };
    const onMove = (moved: globalThis.MouseEvent): void => {
      changeRef.current(nextSplitWidth(origin.width, moved.clientX - origin.clientX, min, max));
    };
    const stop = (): void => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", stop);
      stopRef.current = null;
      setDragging(false);
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", stop);
    stopRef.current = stop;
    setDragging(true);
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>): void {
    const next =
      event.key === "ArrowLeft"
        ? nextSplitWidth(width, -step, min, max)
        : event.key === "ArrowRight"
          ? nextSplitWidth(width, step, min, max)
          : event.key === "Home"
            ? min
            : event.key === "End"
              ? max
              : null;
    if (next === null) {
      return;
    }
    event.preventDefault();
    changeRef.current(next);
  }

  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={ariaLabel}
      aria-valuenow={width}
      aria-valuemin={min}
      aria-valuemax={max}
      tabIndex={0}
      className={dragging ? "ci-split ci-split--dragging" : "ci-split"}
      onMouseDown={onMouseDown}
      onKeyDown={onKeyDown}
    >
      <span className="ci-split__grip" aria-hidden="true" />
    </div>
  );
}

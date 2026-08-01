import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent,
  type ReactElement,
} from "react";

/** 矢印キー1回あたりの移動量(画素)の既定。 */
const DEFAULT_STEP = 24;

/** ハンドルの向き。vertical=左右に並ぶペインの境界、horizontal=上下に並ぶペインの境界。 */
export type SplitOrientation = "vertical" | "horizontal";

/** 寸法を操作する側。after=ハンドルの右・下のペイン、before=ハンドルの左・上のペイン。 */
export type SplitSide = "after" | "before";

export interface SplitHandleProps {
  /** 操作対象のペインの現在の寸法(画素)。vertical なら幅、horizontal なら高さ。 */
  size: number;
  /** 寸法の下限(画素)。このペインが役目を果たす最小である。 */
  min: number;
  /** 相手側(コード面)へ必ず残す寸法(画素)。可動上限はこの値とコンテナの実寸から導く。 */
  oppositeMin: number;
  onSizeChange: (size: number) => void;
  /** ドラッグまたはキー操作が終わった時点で1回だけ呼ぶ。寸法の保存はこれを合図に行う。 */
  onCommit?: () => void;
  /** ハンドルの向き(既定は vertical)。 */
  orientation?: SplitOrientation;
  /** 寸法を操作する側(既定は after)。 */
  side?: SplitSide;
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
 * ハンドルの移動量から新しいペインの寸法を求める。nextSplitWidth に対し、操作対象が
 * ハンドルの前側(左・上)にある場合の符号の反転だけを加える。前側のペインは、ハンドルを
 * 右・下へ動かす(移動量が正)ほど広くなる。
 */
export function nextSplitSize(
  base: number,
  delta: number,
  side: SplitSide,
  min: number,
  max: number,
): number {
  return nextSplitWidth(base, side === "before" ? -delta : delta, min, max);
}

/**
 * 可動上限を求める。上限は画素の定数で持たず、コンテナの実寸から相手側の下限とハンドルの
 * 寸法を引いて導く。定数で持つと、ウィンドウが小さいときは上限まで広げると相手が潰れ、
 * 大きいときはこれ以上広げられない理由が利用者に分からない、の両方が起きる。
 *
 * コンテナをまだ測れていない間(最初の描画やレイアウトを持たない環境)は上限を敷かない。
 * 測れない寸法を 0 とみなして上限を課すと、下限まで縮めてしまうためである。
 */
export function maxSplitSize(
  containerSize: number,
  handleSize: number,
  min: number,
  oppositeMin: number,
): number {
  return containerSize > 0
    ? Math.max(min, containerSize - oppositeMin - handleSize)
    : Number.POSITIVE_INFINITY;
}

/**
 * 隣り合う2つの領域の境界に置く分割ハンドル。片側のペインの寸法を、マウスのドラッグと
 * キーボード(矢印キーで step ずつ、Home で下限、End で上限)で変える。
 *
 * 寸法そのものは持たず、現在値を受け取って変更を通知するだけである。保持は呼び出し側(AppState)が担う。
 */
export function SplitHandle({
  size,
  min,
  oppositeMin,
  onSizeChange,
  onCommit,
  orientation = "vertical",
  side = "after",
  step = DEFAULT_STEP,
  ariaLabel,
}: SplitHandleProps): ReactElement {
  const [dragging, setDragging] = useState(false);
  const [maxSize, setMaxSize] = useState(Number.POSITIVE_INFINITY);
  const handleRef = useRef<HTMLDivElement | null>(null);
  const changeRef = useRef(onSizeChange);
  changeRef.current = onSizeChange;
  const commitRef = useRef(onCommit);
  commitRef.current = onCommit;
  /** ドラッグ中の購読を外す手続き。ドラッグしていない間は null。 */
  const stopRef = useRef<(() => void) | null>(null);
  /** キーを押している間に寸法を変えたか。離した時点で1回だけ保存を促すために持つ。 */
  const keyChangedRef = useRef(false);

  /** 可動上限をコンテナの実寸から求める。ハンドルの寸法は実要素から測り、CSS と二重に持たない。 */
  const measureMax = useCallback((): number => {
    const handle = handleRef.current;
    const container = handle?.parentElement ?? null;
    if (handle === null || container === null) {
      return Number.POSITIVE_INFINITY;
    }
    const containerBox = container.getBoundingClientRect();
    const handleBox = handle.getBoundingClientRect();
    return orientation === "horizontal"
      ? maxSplitSize(containerBox.height, handleBox.height, min, oppositeMin)
      : maxSplitSize(containerBox.width, handleBox.width, min, oppositeMin);
  }, [orientation, min, oppositeMin]);

  // コンテナの寸法が変われば上限も変わる。ResizeObserver がある環境ではコンテナ自身の
  // 変化を、無い環境では窓の寸法変化を合図に測り直す。
  useLayoutEffect(() => {
    const apply = (): void => setMaxSize(measureMax());
    apply();
    const container = handleRef.current?.parentElement ?? null;
    const observer =
      typeof ResizeObserver === "undefined" || container === null
        ? null
        : new ResizeObserver(apply);
    if (container !== null) {
      observer?.observe(container);
    }
    window.addEventListener("resize", apply);
    return () => {
      observer?.disconnect();
      window.removeEventListener("resize", apply);
    };
  }, [measureMax]);

  // 上限を超えた寸法は端で丸める。保存してある寸法を戻した直後や、ウィンドウを縮めた後に、
  // 相手側が下限を割ったままになるのを防ぐ。保存は促さない(窓を縮めただけで利用者が
  // 決めた寸法を上書きしないため、戻したときは元の寸法で開く)。
  useEffect(() => {
    if (size > maxSize) {
      changeRef.current(maxSize);
    }
  }, [size, maxSize]);

  // ドラッグの途中でこの画面を離れた場合に、窓へ残った購読を外す。
  useEffect(() => () => stopRef.current?.(), []);

  /**
   * ドラッグ中はハンドルの外(隣のペインの上)へポインタが出るため、窓全体で移動と解放を受ける。
   * 購読は効果ではなくこの場で張る。効果は描画の後に走るため、掴んだ直後の移動を取り落とす。
   */
  function onMouseDown(event: MouseEvent<HTMLDivElement>): void {
    // 既定の選択開始を止め、ドラッグが隣のペインの本文を選ばないようにする。
    event.preventDefault();
    // 掴んだ時点のコンテナで上限を決める。描画のたびに導くので、窓の寸法に追従する。
    const max = measureMax();
    setMaxSize(max);
    const origin = {
      position: orientation === "horizontal" ? event.clientY : event.clientX,
      size,
    };
    const onMove = (moved: globalThis.MouseEvent): void => {
      const position = orientation === "horizontal" ? moved.clientY : moved.clientX;
      changeRef.current(nextSplitSize(origin.size, position - origin.position, side, min, max));
    };
    const stop = (): void => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", stop);
      stopRef.current = null;
      setDragging(false);
      commitRef.current?.();
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", stop);
    stopRef.current = stop;
    setDragging(true);
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>): void {
    const max = measureMax();
    setMaxSize(max);
    const decreaseKey = orientation === "horizontal" ? "ArrowUp" : "ArrowLeft";
    const increaseKey = orientation === "horizontal" ? "ArrowDown" : "ArrowRight";
    const next =
      event.key === decreaseKey
        ? nextSplitSize(size, -step, side, min, max)
        : event.key === increaseKey
          ? nextSplitSize(size, step, side, min, max)
          : event.key === "Home"
            ? min
            : event.key === "End"
              ? // 上限を測れていない間は現在の寸法に留める(測れない寸法へは飛ばさない)。
                (Number.isFinite(max) ? max : size)
              : null;
    if (next === null) {
      return;
    }
    event.preventDefault();
    keyChangedRef.current = true;
    changeRef.current(next);
  }

  /** キーを離した時点を操作の終わりとみなす。押しっぱなしの繰り返しでも保存は1回である。 */
  function onKeyUp(): void {
    if (!keyChangedRef.current) {
      return;
    }
    keyChangedRef.current = false;
    commitRef.current?.();
  }

  const className = ["ci-split", orientation === "horizontal" ? "ci-split--horizontal" : null, dragging ? "ci-split--dragging" : null]
    .filter((name): name is string => name !== null)
    .join(" ");

  return (
    <div
      ref={handleRef}
      role="separator"
      aria-orientation={orientation}
      aria-label={ariaLabel}
      aria-valuenow={size}
      aria-valuemin={min}
      // 上限はコンテナの実寸から導くため、測れていない間は示さない。
      aria-valuemax={Number.isFinite(maxSize) ? maxSize : undefined}
      tabIndex={0}
      className={className}
      onMouseDown={onMouseDown}
      onKeyDown={onKeyDown}
      onKeyUp={onKeyUp}
    >
      <span className="ci-split__grip" aria-hidden="true" />
    </div>
  );
}

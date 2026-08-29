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

/** How far one arrow-key press moves the handle, in pixels. */
const DEFAULT_STEP = 24;

/** vertical = the boundary between panes side by side; horizontal = between panes stacked. */
export type SplitOrientation = "vertical" | "horizontal";

/** Which pane the handle sizes: the one after it (right/below) or before it (left/above). */
export type SplitSide = "after" | "before";

export interface SplitHandleProps {
  /** The pane's current size in pixels: its width when vertical, its height when horizontal. */
  size: number;
  /** The smallest size at which that pane is still usable. */
  min: number;
  /** What must be left for the pane on the other side; the maximum follows from it. */
  oppositeMin: number;
  onSizeChange: (size: number) => void;
  /** Called once when a drag or a key-repeat ends. Persisting the size keys off this. */
  onCommit?: () => void;
  orientation?: SplitOrientation;
  side?: SplitSide;
  step?: number;
  ariaLabel: string;
}

/**
 * The new size for a given handle movement. The pane after the handle narrows as the handle moves
 * right or down (a positive delta). Fractional pixels are rounded and the result is clamped.
 */
export function nextSplitSize(
  base: number,
  delta: number,
  side: SplitSide,
  min: number,
  max: number,
): number {
  const signed = side === "before" ? -delta : delta;
  return Math.min(max, Math.max(min, Math.round(base - signed)));
}

/**
 * The maximum size, derived from the container rather than held as a constant: subtract the other
 * pane's minimum and the handle itself. A constant would crush the other pane in a small window and
 * stop short for no visible reason in a large one.
 *
 * While the container cannot be measured (the first render, or an environment without layout), no
 * maximum is imposed: treating an unmeasurable size as 0 would clamp the pane down to its minimum.
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
 * The draggable boundary between two regions. It sizes one of them with the mouse and with the
 * keyboard (arrows by `step`, Home for the minimum, End for the maximum).
 *
 * It holds no size of its own: it takes the current value and reports changes. Ownership stays with
 * the caller.
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
  /** How to detach the window listeners mid-drag; null when not dragging. */
  const stopRef = useRef<(() => void) | null>(null);
  /** Whether a held key changed the size, so the commit fires once on release. */
  const keyChangedRef = useRef(false);

  /** Measures the maximum from the container. The handle's own size comes from the element. */
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

  // The maximum moves with the container, so re-measure on container resize where ResizeObserver
  // exists and on window resize where it does not.
  useLayoutEffect(() => {
    const apply = (): void => setMaxSize(measureMax());
    apply();
    const container = handleRef.current?.parentElement ?? null;
    const observer =
      typeof ResizeObserver === "undefined" || container === null ? null : new ResizeObserver(apply);
    if (container !== null) {
      observer?.observe(container);
    }
    window.addEventListener("resize", apply);
    return () => {
      observer?.disconnect();
      window.removeEventListener("resize", apply);
    };
  }, [measureMax]);

  // Clamp a size that exceeds the maximum, which happens right after restoring a stored size or
  // after shrinking the window. No commit follows: shrinking the window must not overwrite the size
  // the user chose, so restoring it reopens at that size.
  useEffect(() => {
    if (size > maxSize) {
      changeRef.current(maxSize);
    }
  }, [size, maxSize]);

  // Detach any listener left on the window if this handle unmounts mid-drag.
  useEffect(() => () => stopRef.current?.(), []);

  /**
   * During a drag the pointer leaves the handle and travels over the neighbouring pane, so movement
   * and release are taken from the window. The listeners are attached here rather than in an effect:
   * an effect runs after the render and would miss the movement immediately after the press.
   */
  function onMouseDown(event: MouseEvent<HTMLDivElement>): void {
    // Suppress the default selection start, so the drag does not select the neighbour's text.
    event.preventDefault();
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
              ? // While the maximum cannot be measured, stay put rather than jump to infinity.
                Number.isFinite(max)
                ? max
                : size
              : null;
    if (next === null) {
      return;
    }
    event.preventDefault();
    keyChangedRef.current = true;
    changeRef.current(next);
  }

  /** Releasing the key ends the gesture, so a key-repeat still commits only once. */
  function onKeyUp(): void {
    if (!keyChangedRef.current) {
      return;
    }
    keyChangedRef.current = false;
    commitRef.current?.();
  }

  const className = [
    "ci-split",
    orientation === "horizontal" ? "ci-split--horizontal" : null,
    dragging ? "ci-split--dragging" : null,
  ]
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
      // The maximum comes from the container, so it is not announced until it can be measured.
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

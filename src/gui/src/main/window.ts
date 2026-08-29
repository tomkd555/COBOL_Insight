import type { BrowserWindowConstructorOptions } from "electron";

/**
 * The window's minimum size. The width matches --ci-min-width in styles/tokens.css; below it the
 * fixed-width parts of the tab bar and status bar leave the viewport and produce a horizontal
 * scrollbar. The height covers the fixed title bar and status bar plus a body area in which the
 * tab bar, the tree and the editor are all usable at once.
 */
export const MIN_WINDOW_WIDTH = 1120;
export const MIN_WINDOW_HEIGHT = 700;

/** A window size in pixels. */
export interface WindowSize {
  readonly width: number;
  readonly height: number;
}

/** The preferred size: wide enough for the side bar and the editor to work together. */
const PREFERRED_SIZE: WindowSize = { width: 1440, height: 900 };

/**
 * Fits the preferred size inside the work area (the screen minus the taskbar). A window larger than
 * the work area puts its edges off screen, out of reach of the title bar and status bar. When the
 * work area itself is smaller than the minimum, the minimum wins, since minWidth/minHeight stop the
 * window shrinking further anyway.
 */
export function clampWindowSize(preferred: WindowSize, workArea: WindowSize): WindowSize {
  return {
    width: Math.max(MIN_WINDOW_WIDTH, Math.min(preferred.width, workArea.width)),
    height: Math.max(MIN_WINDOW_HEIGHT, Math.min(preferred.height, workArea.height)),
  };
}

/**
 * Builds the BrowserWindow constructor options. Passing a work area fits the preferred size into it.
 *
 * The renderer displays imported COBOL source and engine-generated report HTML, so it must not reach
 * Node: contextIsolation and sandbox stay on, and every file read and engine launch goes through the
 * IPC the preload exposes.
 */
export function buildWindowOptions(
  preloadPath: string,
  workArea?: WindowSize,
): BrowserWindowConstructorOptions {
  const size = workArea === undefined ? PREFERRED_SIZE : clampWindowSize(PREFERRED_SIZE, workArea);
  return {
    width: size.width,
    height: size.height,
    minWidth: MIN_WINDOW_WIDTH,
    minHeight: MIN_WINDOW_HEIGHT,
    center: true,
    show: false,
    title: "COBOL Insight",
    backgroundColor: "#1f1f1f",
    webPreferences: {
      preload: preloadPath,
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
    },
  };
}

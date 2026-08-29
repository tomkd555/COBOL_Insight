import { describe, expect, it } from "vitest";
import { MIN_WINDOW_HEIGHT, MIN_WINDOW_WIDTH, buildWindowOptions, clampWindowSize } from "./window";

describe("clampWindowSize", () => {
  it("keeps the preferred size when the work area is large enough", () => {
    expect(clampWindowSize({ width: 1440, height: 900 }, { width: 1920, height: 1080 })).toEqual({
      width: 1440,
      height: 900,
    });
  });

  it("shrinks to the work area rather than putting the window edges off screen", () => {
    expect(clampWindowSize({ width: 1440, height: 900 }, { width: 1366, height: 768 })).toEqual({
      width: 1366,
      height: 768,
    });
  });

  it("never goes below the minimum, which minWidth/minHeight enforce anyway", () => {
    expect(clampWindowSize({ width: 1440, height: 900 }, { width: 800, height: 600 })).toEqual({
      width: MIN_WINDOW_WIDTH,
      height: MIN_WINDOW_HEIGHT,
    });
  });
});

describe("buildWindowOptions", () => {
  it("keeps the renderer away from Node", () => {
    const options = buildWindowOptions("C:/app/preload.js");
    expect(options.webPreferences).toMatchObject({
      preload: "C:/app/preload.js",
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
    });
  });

  it("starts hidden so the window appears only once it can be drawn", () => {
    expect(buildWindowOptions("p.js").show).toBe(false);
  });

  it("fits the preferred size into the work area when one is given", () => {
    expect(buildWindowOptions("p.js", { width: 1280, height: 800 })).toMatchObject({
      width: 1280,
      height: 800,
    });
  });
});

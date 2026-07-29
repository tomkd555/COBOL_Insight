import { describe, it, expect } from "vitest";
import { buildWindowOptions, MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT } from "./windowOptions";

describe("buildWindowOptions", () => {
  it("最小幅・最小高さを設定する", () => {
    const options = buildWindowOptions("/preload/index.js");
    expect(options.minWidth).toBe(MIN_WINDOW_WIDTH);
    expect(options.minHeight).toBe(MIN_WINDOW_HEIGHT);
  });

  it("既定サイズは最小寸法を下回らない", () => {
    const options = buildWindowOptions("/preload/index.js");
    expect(options.width).toBeGreaterThanOrEqual(MIN_WINDOW_WIDTH);
    expect(options.height).toBeGreaterThanOrEqual(MIN_WINDOW_HEIGHT);
  });

  it("preload のパスをそのまま webPreferences へ渡す", () => {
    const options = buildWindowOptions("/preload/index.js");
    expect(options.webPreferences?.preload).toBe("/preload/index.js");
  });
});

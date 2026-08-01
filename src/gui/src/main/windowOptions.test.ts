import { describe, it, expect } from "vitest";
import {
  buildWindowOptions,
  clampWindowSize,
  MIN_WINDOW_WIDTH,
  MIN_WINDOW_HEIGHT,
} from "./windowOptions";

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

  it("作業領域に収まる既定サイズにする", () => {
    const options = buildWindowOptions("/preload/index.js", { width: 1280, height: 800 });
    expect(options.width).toBe(1280);
    expect(options.height).toBe(800);
    expect(options.center).toBe(true);
  });
});

describe("clampWindowSize", () => {
  const preferred = { width: 1440, height: 900 };

  it("作業領域が広ければ希望する寸法をそのまま使う", () => {
    expect(clampWindowSize(preferred, { width: 1920, height: 1080 })).toEqual(preferred);
  });

  it("作業領域より大きくしない(画面の外へ出さない)", () => {
    expect(clampWindowSize(preferred, { width: 1366, height: 768 })).toEqual({
      width: 1366,
      height: 768,
    });
  });

  it("作業領域が最小寸法より狭くても最小寸法は下回らない", () => {
    expect(clampWindowSize(preferred, { width: 800, height: 600 })).toEqual({
      width: MIN_WINDOW_WIDTH,
      height: MIN_WINDOW_HEIGHT,
    });
  });
});

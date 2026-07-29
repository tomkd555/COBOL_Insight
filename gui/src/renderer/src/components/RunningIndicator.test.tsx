import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { RunningIndicator, RUN_STAGES } from "./RunningIndicator";

describe("RunningIndicator", () => {
  it("既定で解析実行の3段(scan→lint→sql-advise)の進行を提示する", () => {
    render(<RunningIndicator />);
    expect(RUN_STAGES).toEqual([
      "第1段 資産の走査と構文解析",
      "第2段 バグ検出",
      "第3段 SQL助言",
    ]);
    RUN_STAGES.forEach((label) => {
      expect(screen.getByText(label)).toBeInTheDocument();
    });
  });

  it("title を上書きできる", () => {
    render(<RunningIndicator title="バグ検出を実行しています…" />);
    expect(screen.getByText("バグ検出を実行しています…")).toBeInTheDocument();
  });

  it("aria-live=polite の status として読み上げる", () => {
    render(<RunningIndicator />);
    const status = screen.getByRole("status");
    expect(status).toHaveAttribute("aria-live", "polite");
  });

  it("activeStage を渡すと該当段を現在ステップとして示す", () => {
    render(<RunningIndicator activeStage={2} />);
    const current = screen.getByText("第2段 バグ検出");
    expect(current).toHaveAttribute("aria-current", "step");
  });
});

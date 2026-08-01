import type { ComponentProps } from "react";
import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { Shell } from "./Shell";
import { SCREENS } from "./screens";

function renderShell(overrides: Partial<ComponentProps<typeof Shell>> = {}) {
  return render(
    <Shell
      tabs={SCREENS}
      activeScreen="explorer"
      onSelectScreen={() => {}}
      statusLeft="準備完了"
      statusCounts="資産 0 ・ ルール 37 有効"
      {...overrides}
    >
      <div data-testid="screen-content">内容</div>
    </Shell>,
  );
}

describe("Shell", () => {
  it("5段の固定要素(タイトルバー・タブバー・コンテンツ・ステータスバー)を描画する", () => {
    renderShell();
    expect(screen.getByRole("banner")).toBeInTheDocument();
    expect(screen.getByRole("banner")).toHaveTextContent("COBOL Insight");
    expect(screen.getByRole("tablist")).toBeInTheDocument();
    expect(screen.getByTestId("screen-content")).toBeInTheDocument();
    const status = screen.getByRole("contentinfo");
    expect(status).toHaveTextContent("準備完了");
    expect(status).toHaveTextContent("資産 0 ・ ルール 37 有効");
  });

  it("非実行中は進捗バーを描画しない", () => {
    renderShell({ isRunning: false });
    expect(screen.queryByRole("progressbar")).toBeNull();
  });

  it("実行中は進捗バーとタイトルバーの実行中表示を描画する", () => {
    renderShell({ isRunning: true, runningLabel: "SYK006.cbl（13 / 19 件）" });
    expect(screen.getByRole("progressbar")).toBeInTheDocument();
    expect(screen.getByRole("banner")).toHaveTextContent("解析実行中 ― SYK006.cbl（13 / 19 件）");
  });

  it("toast を渡すとメッセージを表示する", () => {
    renderShell({ toast: "解析が完了しました" });
    expect(screen.getByText("解析が完了しました")).toBeInTheDocument();
  });
});

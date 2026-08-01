import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { SeverityBadge } from "./SeverityBadge";

describe("SeverityBadge", () => {
  it("重大度ごとに記号とラベルを表示する", () => {
    const { rerender } = render(<SeverityBadge severity="high" />);
    expect(screen.getByLabelText("重大度: 高")).toHaveTextContent("●高");
    rerender(<SeverityBadge severity="warning" />);
    expect(screen.getByLabelText("重大度: 警告")).toHaveTextContent("▲警告");
  });

  it("色トークンを結び付ける重大度別の修飾子クラスを付与する", () => {
    render(<SeverityBadge severity="medium" />);
    const badge = screen.getByLabelText("重大度: 中");
    expect(badge).toHaveClass("ci-severity-badge", "ci-severity-badge--medium");
  });

  it("記号は装飾として aria から隠す", () => {
    render(<SeverityBadge severity="low" />);
    const symbol = screen.getByLabelText("重大度: 低").querySelector(".ci-severity-badge__symbol");
    expect(symbol).toHaveAttribute("aria-hidden", "true");
  });

  it("showLabel=false ではラベルを省き記号のみ表示する", () => {
    render(<SeverityBadge severity="high" showLabel={false} />);
    const badge = screen.getByLabelText("重大度: 高");
    expect(badge).toHaveTextContent("●");
    expect(badge.querySelector(".ci-severity-badge__label")).toBeNull();
  });
});

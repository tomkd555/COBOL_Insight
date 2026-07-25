import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { FilterChip } from "./FilterChip";

describe("FilterChip", () => {
  it("ラベルと件数を表示する", () => {
    render(<FilterChip label="高" count={3} />);
    const chip = screen.getByRole("button", { name: /高/ });
    expect(chip).toHaveTextContent("高");
    expect(chip).toHaveTextContent("3");
  });

  it("件数省略時は件数を表示しない", () => {
    render(<FilterChip label="すべて" />);
    expect(screen.getByRole("button", { name: "すべて" }).querySelector(".ci-chip__count")).toBeNull();
  });

  it("単一選択(single)はラジオとして role と aria-checked で表す", () => {
    const { rerender } = render(<FilterChip label="COBOL" single active={false} />);
    const chip = screen.getByRole("radio", { name: "COBOL" });
    expect(chip).toHaveAttribute("aria-checked", "false");
    expect(chip).not.toHaveAttribute("aria-pressed");
    rerender(<FilterChip label="COBOL" single active={true} />);
    expect(chip).toHaveAttribute("aria-checked", "true");
    expect(chip).toHaveClass("ci-chip--active");
  });

  it("選択状態を aria-pressed と修飾子クラスで表す", () => {
    const { rerender } = render(<FilterChip label="COBOL" active={false} />);
    const chip = screen.getByRole("button", { name: "COBOL" });
    expect(chip).toHaveAttribute("aria-pressed", "false");
    expect(chip).not.toHaveClass("ci-chip--active");
    rerender(<FilterChip label="COBOL" active={true} />);
    expect(chip).toHaveAttribute("aria-pressed", "true");
    expect(chip).toHaveClass("ci-chip--active");
  });

  it("クリックで onClick を呼ぶ", () => {
    const onClick = vi.fn();
    render(<FilterChip label="JCL" onClick={onClick} />);
    fireEvent.click(screen.getByRole("button", { name: "JCL" }));
    expect(onClick).toHaveBeenCalledOnce();
  });

  it("記号付きチップは記号を装飾として表示し色トークンを適用する", () => {
    render(<FilterChip label="高" symbol="●" symbolColorVar="var(--ci-sev-high)" count={3} />);
    const symbol = screen.getByRole("button", { name: /高/ }).querySelector(".ci-chip__symbol");
    expect(symbol).toHaveTextContent("●");
    expect(symbol).toHaveAttribute("aria-hidden", "true");
    expect(symbol).toHaveStyle({ color: "var(--ci-sev-high)" });
  });
});

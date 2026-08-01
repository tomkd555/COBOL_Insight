import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { TabBar } from "./TabBar";
import { SCREENS } from "./screens";

describe("TabBar", () => {
  it("9画面すべてをタブとして tablist に並べる", () => {
    render(<TabBar tabs={SCREENS} activeId="explorer" onSelect={() => {}} />);
    const tabs = screen.getAllByRole("tab");
    expect(tabs).toHaveLength(9);
    expect(screen.getByRole("tablist")).toBeInTheDocument();
  });

  it("選択タブを aria-selected=true と修飾子クラスで示し、非選択は false にする", () => {
    render(<TabBar tabs={SCREENS} activeId="findings" onSelect={() => {}} />);
    const active = screen.getByRole("tab", { name: "指摘一覧" });
    expect(active).toHaveAttribute("aria-selected", "true");
    expect(active).toHaveClass("ci-tab--active");
    expect(active).toHaveAttribute("tabindex", "0");
    const other = screen.getByRole("tab", { name: "設定" });
    expect(other).toHaveAttribute("aria-selected", "false");
    expect(other).toHaveAttribute("tabindex", "-1");
  });

  it("タブクリックで onSelect にその画面 ID を渡す", () => {
    const onSelect = vi.fn();
    render(<TabBar tabs={SCREENS} activeId="explorer" onSelect={onSelect} />);
    fireEvent.click(screen.getByRole("tab", { name: "呼出関係図" }));
    expect(onSelect).toHaveBeenCalledWith("graph");
  });

  it("矢印キーでタブを移し、焦点も移る(単一タブストップの操作規約)", () => {
    const onSelect = vi.fn();
    const { rerender } = render(<TabBar tabs={SCREENS} activeId="explorer" onSelect={onSelect} />);
    fireEvent.keyDown(screen.getByRole("tablist"), { key: "ArrowRight" });
    expect(onSelect).toHaveBeenCalledWith("import");
    expect(screen.getByRole("tab", { name: "端末取込" })).toHaveFocus();

    rerender(<TabBar tabs={SCREENS} activeId="import" onSelect={onSelect} />);
    fireEvent.keyDown(screen.getByRole("tablist"), { key: "ArrowLeft" });
    expect(onSelect).toHaveBeenLastCalledWith("explorer");
    expect(screen.getByRole("tab", { name: "資産エクスプローラー" })).toHaveFocus();
  });

  it("端では反対の端へ回り、Home・End で端へ移る", () => {
    const onSelect = vi.fn();
    render(<TabBar tabs={SCREENS} activeId="explorer" onSelect={onSelect} />);
    fireEvent.keyDown(screen.getByRole("tablist"), { key: "ArrowLeft" });
    expect(onSelect).toHaveBeenLastCalledWith("settings");
    fireEvent.keyDown(screen.getByRole("tablist"), { key: "End" });
    expect(onSelect).toHaveBeenLastCalledWith("settings");
    fireEvent.keyDown(screen.getByRole("tablist"), { key: "Home" });
    expect(onSelect).toHaveBeenLastCalledWith("explorer");
  });

  it("関係のないキーではタブを移さない", () => {
    const onSelect = vi.fn();
    render(<TabBar tabs={SCREENS} activeId="explorer" onSelect={onSelect} />);
    fireEvent.keyDown(screen.getByRole("tablist"), { key: "a" });
    expect(onSelect).not.toHaveBeenCalled();
  });

  it("タブは対応する tabpanel を aria-controls で指す", () => {
    render(<TabBar tabs={SCREENS} activeId="explorer" onSelect={() => {}} />);
    expect(screen.getByRole("tab", { name: "修正案の差分" })).toHaveAttribute("aria-controls", "ci-screen-diff");
  });

  it("nav ランドマークが tablist を包み、aria-label を持つ", () => {
    render(<TabBar tabs={SCREENS} activeId="explorer" onSelect={() => {}} />);
    const nav = screen.getByRole("navigation", { name: "画面切り替え" });
    expect(nav).toContainElement(screen.getByRole("tablist"));
  });
});

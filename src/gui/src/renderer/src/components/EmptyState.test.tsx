import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { EmptyState } from "./EmptyState";

describe("EmptyState", () => {
  it("タイトルを名前とする領域として見出しと説明を表示する", () => {
    render(<EmptyState title="資産がまだインポートされていません" description="フォルダを取り込みます。" />);
    const region = screen.getByRole("region", { name: "資産がまだインポートされていません" });
    expect(region).toHaveTextContent("資産がまだインポートされていません");
    expect(region).toHaveTextContent("フォルダを取り込みます。");
  });

  it("actionLabel を渡すと主アクションボタンを表示しクリックで onAction を呼ぶ", () => {
    const onAction = vi.fn();
    render(<EmptyState title="空" actionLabel="インポート" onAction={onAction} />);
    const button = screen.getByRole("button", { name: "インポート" });
    fireEvent.click(button);
    expect(onAction).toHaveBeenCalledOnce();
  });

  it("actionLabel を渡さないときはボタンを出さない", () => {
    render(<EmptyState title="空" />);
    expect(screen.queryByRole("button")).toBeNull();
  });
});

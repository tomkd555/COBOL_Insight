import { render, screen } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { CopybookPathList } from "./CopybookPathList";

const PATHS = ["C:\\資産\\copybook", "C:\\無い\\パス"];

function renderList(overrides: Partial<Parameters<typeof CopybookPathList>[0]> = {}): void {
  render(
    <CopybookPathList
      paths={PATHS}
      draft=""
      onDraftChange={vi.fn()}
      onMove={vi.fn()}
      onRemove={vi.fn()}
      onAdd={vi.fn()}
      disabled={false}
      existence={{}}
      addWarning={null}
      {...overrides}
    />,
  );
}

describe("CopybookPathList の実在確認の表示", () => {
  it("存在確認がまだ済んでいないパスには警告を出さない", () => {
    renderList({ existence: {} });
    expect(screen.queryByText(/見つからない/)).toBeNull();
  });

  it("実在しないと判定したパスの行に記号とテキストで警告を出す", () => {
    renderList({ existence: { "C:\\資産\\copybook": true, "C:\\無い\\パス": false } });
    const warning = screen.getByText(/見つからない/);
    expect(warning.textContent).toMatch(/⚠/);
  });

  it("実在すると判定したパスの行には警告を出さない", () => {
    renderList({ existence: { "C:\\資産\\copybook": true, "C:\\無い\\パス": true } });
    expect(screen.queryByText(/見つからない/)).toBeNull();
  });

  it("入力欄の近くに追加時の実在確認の警告を出す", () => {
    renderList({ addWarning: "D:\\存在しない が見つかりません。" });
    expect(screen.getByRole("alert")).toHaveTextContent("D:\\存在しない が見つかりません。");
  });

  it("追加時の警告が無ければ出さない", () => {
    renderList({ addWarning: null });
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("追加時の警告のあいだ入力欄を入力エラーとし、警告文を説明として結びつける", () => {
    renderList({ addWarning: "D:\\存在しない が見つかりません。" });
    const input = screen.getByRole("textbox", { name: "追加するコピー句探索パス" });
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveClass("ci-input--invalid");
    expect(input).toHaveAccessibleDescription("⚠ D:\\存在しない が見つかりません。");
  });

  it("追加時の警告が無ければ入力欄を入力エラーとしない", () => {
    renderList({ addWarning: null });
    const input = screen.getByRole("textbox", { name: "追加するコピー句探索パス" });
    expect(input).not.toHaveAttribute("aria-invalid");
    expect(input).not.toHaveAccessibleDescription();
  });
});

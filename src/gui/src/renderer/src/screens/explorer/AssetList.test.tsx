import { render, screen, fireEvent, within } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { AssetList } from "./AssetList";
import { buildAssetGroups } from "./assetView";
import { SAMPLE_INVENTORY } from "./fixtures";

const groups = buildAssetGroups(SAMPLE_INVENTORY, {
  search: "",
  type: "すべて",
  encodingSel: {},
  mode: "results",
  selectedPath: "cobol/SYK001.cbl",
  findingCounts: { "cobol/SYK001.cbl": 4 },
});

function renderList(over?: { collapsed?: Set<string>; onToggleDir?: () => void; onSelect?: (p: string) => void }) {
  render(
    <AssetList
      groups={groups}
      collapsed={over?.collapsed ?? new Set()}
      showFindingColumn={true}
      onToggleDir={over?.onToggleDir ?? vi.fn()}
      onSelect={over?.onSelect ?? vi.fn()}
    />,
  );
}

describe("AssetList(資産一覧)", () => {
  it("名前/種別/文字コード/解析/指摘の列見出しを出す", () => {
    renderList();
    ["名前", "種別", "文字コード", "解析", "指摘"].forEach((label) => {
      expect(screen.getByText(label)).toBeInTheDocument();
    });
  });

  it("表・グリッドのロールを持たないため row ロールを与えない(ARIA の整合)", () => {
    renderList();
    expect(screen.queryAllByRole("row")).toHaveLength(0);
  });

  it("ディレクトリ見出しとファイル数を出す", () => {
    renderList();
    const cobolHeader = screen.getByRole("button", { name: /cobol/ });
    expect(cobolHeader).toHaveTextContent("3 ファイル");
    expect(cobolHeader).toHaveAttribute("aria-expanded", "true");
  });

  it("行に種別バッジ・文字コード・状態・指摘件数を描く", () => {
    renderList();
    const row = screen.getByRole("button", { name: /SYK001\.cbl/ });
    expect(within(row).getByText("COBOL")).toBeInTheDocument();
    expect(within(row).getByText("Shift_JIS")).toBeInTheDocument();
    expect(within(row).getByText("✓ 解析済")).toBeInTheDocument();
    expect(within(row).getByText("4")).toBeInTheDocument();
  });

  it("選択中の行に aria-pressed=true を立てる", () => {
    renderList();
    expect(screen.getByRole("button", { name: /SYK001\.cbl/ })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: /SYK002\.cbl/ })).toHaveAttribute("aria-pressed", "false");
  });

  it("行クリックで onSelect にパスを渡す", () => {
    const onSelect = vi.fn();
    renderList({ onSelect });
    fireEvent.click(screen.getByRole("button", { name: /SYK002\.cbl/ }));
    expect(onSelect).toHaveBeenCalledWith("cobol/SYK002.cbl");
  });

  it("ディレクトリ見出しクリックで onToggleDir を呼ぶ", () => {
    const onToggleDir = vi.fn();
    renderList({ onToggleDir });
    fireEvent.click(screen.getByRole("button", { name: /cobol/ }));
    expect(onToggleDir).toHaveBeenCalledWith("cobol");
  });

  it("折りたたみ中のディレクトリは行を出さず aria-expanded=false", () => {
    renderList({ collapsed: new Set(["cobol"]) });
    expect(screen.getByRole("button", { name: /cobol/ })).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("button", { name: /SYK001\.cbl/ })).toBeNull();
    expect(screen.getByRole("button", { name: /SYKMAP1\.bms/ })).toBeInTheDocument();
  });

  it("グループが空なら該当なしを出す", () => {
    render(
      <AssetList groups={[]} collapsed={new Set()} showFindingColumn={true} onToggleDir={vi.fn()} onSelect={vi.fn()} />,
    );
    expect(screen.getByText("該当する資産がない。")).toBeInTheDocument();
  });

  it("文字コード列が未判定の行を含むとき、既定の文字コードで表示する旨の注記を出す", () => {
    // SAMPLE_INVENTORY の SYKENC1.cbl は codepage=null で、行の文字コード列は「未判定」になる。
    renderList();
    const row = screen.getByRole("button", { name: /SYKENC1\.cbl/ });
    expect(within(row).getByText("未判定")).toBeInTheDocument();
    expect(screen.getByText(/既定文字コード/)).toBeInTheDocument();
  });

  it("すべての行が判定済みなら注記を出さない", () => {
    const determinedGroups = buildAssetGroups(
      SAMPLE_INVENTORY.filter((entry) => entry.codepage !== null),
      {
        search: "",
        type: "すべて",
        encodingSel: {},
        mode: "results",
        selectedPath: "",
        findingCounts: {},
      },
    );
    render(
      <AssetList
        groups={determinedGroups}
        collapsed={new Set()}
        showFindingColumn={true}
        onToggleDir={vi.fn()}
        onSelect={vi.fn()}
      />,
    );
    expect(screen.queryByText(/既定文字コード/)).toBeNull();
  });
});

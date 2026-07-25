import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { AssetDetail } from "./AssetDetail";
import { SAMPLE_INVENTORY } from "./fixtures";
import type { SourcePreview } from "./sourcePreview";
import type { AssetInventoryItem } from "../../../../shared/engine-api";

const cobol = SAMPLE_INVENTORY.find((i) => i.path === "cobol/SYK001.cbl") as AssetInventoryItem;
const failed = SAMPLE_INVENTORY.find((i) => i.path === "cobol/SYKENC1.cbl") as AssetInventoryItem;

const idle: SourcePreview = { status: "idle" };

describe("AssetDetail(選択資産の詳細)", () => {
  it("未選択のときは選択を促す", () => {
    render(<AssetDetail item={null} mode="results" findingCount={0} encodingValue="" onEncodingChange={vi.fn()} preview={idle} />);
    expect(screen.getByText(/資産を選択すると詳細と/)).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "文字コード" })).toBeNull();
  });

  it("選択資産の名称・パス・種別・解析状態と lint 指摘件数を出す", () => {
    render(
      <AssetDetail item={cobol} mode="results" findingCount={4} encodingValue="自動判定: Shift_JIS" onEncodingChange={vi.fn()} preview={idle} />,
    );
    expect(screen.getByText("SYK001.cbl")).toBeInTheDocument();
    expect(screen.getByText("cobol/SYK001.cbl")).toBeInTheDocument();
    expect(screen.getByText("COBOL")).toBeInTheDocument();
    expect(screen.getByText("✓ 解析済")).toBeInTheDocument();
    expect(screen.getByText("4 件")).toBeInTheDocument();
  });

  it("文字コード選択欄を現在値付きで出す", () => {
    render(
      <AssetDetail item={cobol} mode="results" findingCount={4} encodingValue="手動: EBCDIC CP930" onEncodingChange={vi.fn()} preview={idle} />,
    );
    const select = screen.getByRole("combobox", { name: "文字コード" });
    expect(select).toHaveValue("手動: EBCDIC CP930");
    fireEvent.change(select, { target: { value: "手動: UTF-8" } });
  });

  it("文字コード変更を onEncodingChange へ伝える", () => {
    const onEncodingChange = vi.fn();
    render(
      <AssetDetail item={cobol} mode="results" findingCount={4} encodingValue="自動判定: Shift_JIS" onEncodingChange={onEncodingChange} preview={idle} />,
    );
    fireEvent.change(screen.getByRole("combobox", { name: "文字コード" }), { target: { value: "手動: UTF-8" } });
    expect(onEncodingChange).toHaveBeenCalledWith("手動: UTF-8");
  });

  it("復号できた本文をプレビューへ流す", () => {
    render(
      <AssetDetail
        item={cobol}
        mode="results"
        findingCount={0}
        encodingValue="自動判定: Shift_JIS"
        onEncodingChange={vi.fn()}
        preview={{ status: "ready", lines: ["001000 IDENTIFICATION DIVISION."], codepage: "Shift_JIS" }}
      />,
    );
    expect(screen.getByText("001000 IDENTIFICATION DIVISION.")).toBeInTheDocument();
  });

  it("復号非対応の資産は表示非対応の旨を出す", () => {
    render(
      <AssetDetail
        item={failed}
        mode="error"
        findingCount={0}
        encodingValue="自動判定: UTF-8"
        onEncodingChange={vi.fn()}
        preview={{ status: "unsupported", codepage: "不明" }}
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("表示に対応していません");
  });
});

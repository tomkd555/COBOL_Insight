import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { EncodingSelect } from "./EncodingSelect";
import { ENCODING_OPTIONS } from "./assetView";

describe("EncodingSelect(文字コード選択)", () => {
  it("自動判定2件・EBCDIC 推定2件・手動4件の計8選択肢を提示する", () => {
    render(<EncodingSelect value="自動判定: UTF-8" onChange={vi.fn()} />);
    const options = screen.getAllByRole("option");
    expect(options).toHaveLength(8);
    expect(options.map((o) => o.textContent)).toEqual([...ENCODING_OPTIONS]);
  });

  it("EBCDIC CP930/CP939 を手動選択肢に含む", () => {
    render(<EncodingSelect value="自動判定: UTF-8" onChange={vi.fn()} />);
    expect(screen.getByRole("option", { name: "手動: EBCDIC CP930" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "手動: EBCDIC CP939" })).toBeInTheDocument();
  });

  it("現在値を選択状態にする", () => {
    render(<EncodingSelect value="手動: Shift_JIS" onChange={vi.fn()} />);
    expect(screen.getByRole("combobox", { name: "文字コード" })).toHaveValue("手動: Shift_JIS");
  });

  it("変更で選んだ値を onChange へ渡す", () => {
    const onChange = vi.fn();
    render(<EncodingSelect value="自動判定: UTF-8" onChange={onChange} />);
    fireEvent.change(screen.getByRole("combobox", { name: "文字コード" }), {
      target: { value: "手動: EBCDIC CP930" },
    });
    expect(onChange).toHaveBeenCalledWith("手動: EBCDIC CP930");
  });
});

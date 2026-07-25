import { render, screen, fireEvent } from "@testing-library/react";
import { useState, type ReactElement } from "react";
import { describe, it, expect, vi } from "vitest";
import { ChipRadioGroup } from "./ChipRadioGroup";

const OPTIONS = ["すべて", "JCL", "COBOL"] as const;
type Option = (typeof OPTIONS)[number];

/** 選択の変化を実際に反映する器(roving tabindex の追従を観測する)。 */
function Harness({ initial = "すべて" }: { initial?: Option }): ReactElement {
  const [value, setValue] = useState<Option>(initial);
  return <ChipRadioGroup label="種別フィルタ" options={OPTIONS} value={value} onChange={setValue} />;
}

describe("ChipRadioGroup(単一選択のチップ群)", () => {
  it("ラジオグループとして選択肢を並べ、選択中を aria-checked で示す", () => {
    render(<Harness />);
    expect(screen.getByRole("radiogroup", { name: "種別フィルタ" })).toBeInTheDocument();
    expect(screen.getAllByRole("radio")).toHaveLength(3);
    expect(screen.getByRole("radio", { name: "すべて" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("radio", { name: "JCL" })).toHaveAttribute("aria-checked", "false");
  });

  it("選択中だけ tabindex=0 で、グループ全体が 1 つのタブストップになる", () => {
    render(<Harness initial="COBOL" />);
    expect(screen.getByRole("radio", { name: "COBOL" })).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("radio", { name: "すべて" })).toHaveAttribute("tabindex", "-1");
    expect(screen.getByRole("radio", { name: "JCL" })).toHaveAttribute("tabindex", "-1");
  });

  it("→ キーで次の選択肢へ移り、焦点も移る", () => {
    render(<Harness />);
    fireEvent.keyDown(screen.getByRole("radio", { name: "すべて" }), { key: "ArrowRight" });
    const jcl = screen.getByRole("radio", { name: "JCL" });
    expect(jcl).toHaveAttribute("aria-checked", "true");
    expect(jcl).toHaveAttribute("tabindex", "0");
    expect(jcl).toHaveFocus();
  });

  it("↓ キーも次へ、← ↑ キーは前へ移る", () => {
    render(<Harness initial="JCL" />);
    fireEvent.keyDown(screen.getByRole("radio", { name: "JCL" }), { key: "ArrowDown" });
    expect(screen.getByRole("radio", { name: "COBOL" })).toHaveFocus();
    fireEvent.keyDown(screen.getByRole("radio", { name: "COBOL" }), { key: "ArrowUp" });
    expect(screen.getByRole("radio", { name: "JCL" })).toHaveFocus();
    fireEvent.keyDown(screen.getByRole("radio", { name: "JCL" }), { key: "ArrowLeft" });
    expect(screen.getByRole("radio", { name: "すべて" })).toHaveFocus();
  });

  it("端では反対の端へ回る", () => {
    render(<Harness />);
    fireEvent.keyDown(screen.getByRole("radio", { name: "すべて" }), { key: "ArrowLeft" });
    expect(screen.getByRole("radio", { name: "COBOL" })).toHaveFocus();
    fireEvent.keyDown(screen.getByRole("radio", { name: "COBOL" }), { key: "ArrowRight" });
    expect(screen.getByRole("radio", { name: "すべて" })).toHaveFocus();
  });

  it("Home・End で端へ移る", () => {
    render(<Harness initial="JCL" />);
    fireEvent.keyDown(screen.getByRole("radio", { name: "JCL" }), { key: "End" });
    expect(screen.getByRole("radio", { name: "COBOL" })).toHaveFocus();
    fireEvent.keyDown(screen.getByRole("radio", { name: "COBOL" }), { key: "Home" });
    expect(screen.getByRole("radio", { name: "すべて" })).toHaveFocus();
  });

  it("関係のないキーでは選択を変えない", () => {
    const onChange = vi.fn();
    render(<ChipRadioGroup label="種別フィルタ" options={OPTIONS} value="すべて" onChange={onChange} />);
    fireEvent.keyDown(screen.getByRole("radio", { name: "すべて" }), { key: "a" });
    expect(onChange).not.toHaveBeenCalled();
  });

  it("クリックでも選択を変える", () => {
    const onChange = vi.fn();
    render(<ChipRadioGroup label="種別フィルタ" options={OPTIONS} value="すべて" onChange={onChange} />);
    fireEvent.click(screen.getByRole("radio", { name: "COBOL" }));
    expect(onChange).toHaveBeenCalledWith("COBOL");
  });
});

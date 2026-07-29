import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { Button } from "./Button";
import { TextInput } from "./TextInput";

describe("Button", () => {
  it("既定 variant は default、type は button", () => {
    render(<Button>実行</Button>);
    const button = screen.getByRole("button", { name: "実行" });
    expect(button).toHaveClass("ci-btn", "ci-btn--default");
    expect(button).toHaveAttribute("type", "button");
  });

  it("variant=primary で主アクションの修飾子を付ける", () => {
    render(<Button variant="primary">解析実行</Button>);
    expect(screen.getByRole("button", { name: "解析実行" })).toHaveClass("ci-btn--primary");
  });

  it("onClick と aria-label を透過する", () => {
    const onClick = vi.fn();
    render(
      <Button onClick={onClick} aria-label="解析を実行">
        ▶
      </Button>,
    );
    const button = screen.getByRole("button", { name: "解析を実行" });
    fireEvent.click(button);
    expect(onClick).toHaveBeenCalledOnce();
  });
});

describe("TextInput", () => {
  it("aria-label でアクセシブル名を持ち value 変更を透過する", () => {
    const onChange = vi.fn();
    render(<TextInput aria-label="名前でフィルタ" placeholder="名前でフィルタ" onChange={onChange} />);
    const input = screen.getByRole("textbox", { name: "名前でフィルタ" });
    expect(input).toHaveClass("ci-input");
    fireEvent.change(input, { target: { value: "SYK" } });
    expect(onChange).toHaveBeenCalledOnce();
  });

  it("既定では入力エラーの修飾子も aria-invalid も付けない", () => {
    render(<TextInput aria-label="探索パス" />);
    const input = screen.getByRole("textbox", { name: "探索パス" });
    expect(input).not.toHaveClass("ci-input--invalid");
    expect(input).not.toHaveAttribute("aria-invalid");
  });

  it("invalid で修飾子と aria-invalid を付け、説明を aria-describedby で結びつける", () => {
    render(
      <>
        <TextInput aria-label="探索パス" invalid aria-describedby="path-error" />
        <p id="path-error">フォルダが実在しない。</p>
      </>,
    );
    const input = screen.getByRole("textbox", { name: "探索パス" });
    expect(input).toHaveClass("ci-input", "ci-input--invalid");
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAccessibleDescription("フォルダが実在しない。");
  });

  it("disabled を透過する", () => {
    render(<TextInput aria-label="探索パス" disabled />);
    expect(screen.getByRole("textbox", { name: "探索パス" })).toBeDisabled();
  });
});

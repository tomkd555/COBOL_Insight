import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { ScreenFrame } from "./ScreenFrame";

const empty = <div data-testid="empty-slot">空状態</div>;
const body = <div data-testid="results-body">本体</div>;
const banner = <div data-testid="error-banner">エラーバナー</div>;

describe("ScreenFrame(4状態枠)", () => {
  it("empty は空状態スロットのみ描画する", () => {
    render(
      <ScreenFrame mode="empty" empty={empty} errorBanner={banner}>
        {body}
      </ScreenFrame>,
    );
    expect(screen.getByTestId("empty-slot")).toBeInTheDocument();
    expect(screen.queryByTestId("results-body")).toBeNull();
    expect(screen.queryByTestId("error-banner")).toBeNull();
  });

  it("running は実行中インジケータと3段の進行提示を描画する", () => {
    render(
      <ScreenFrame mode="running" empty={empty}>
        {body}
      </ScreenFrame>,
    );
    expect(screen.queryByTestId("results-body")).toBeNull();
    expect(screen.getByRole("status")).toBeInTheDocument();
    expect(screen.getByText(/第1段 資産の走査と構文解析\(scan\)/)).toBeInTheDocument();
    expect(screen.getByText(/第2段 バグ検出\(lint\)/)).toBeInTheDocument();
    expect(screen.getByText(/第3段 SQL助言\(sql-advise\)/)).toBeInTheDocument();
  });

  it("results は本体のみ描画し、エラーバナーは出さない", () => {
    render(
      <ScreenFrame mode="results" empty={empty} errorBanner={banner}>
        {body}
      </ScreenFrame>,
    );
    expect(screen.getByTestId("results-body")).toBeInTheDocument();
    expect(screen.queryByTestId("empty-slot")).toBeNull();
    expect(screen.queryByTestId("error-banner")).toBeNull();
  });

  it("error は本体とエラーバナーの両方を描画する", () => {
    render(
      <ScreenFrame mode="error" empty={empty} errorBanner={banner}>
        {body}
      </ScreenFrame>,
    );
    expect(screen.getByTestId("results-body")).toBeInTheDocument();
    expect(screen.getByTestId("error-banner")).toBeInTheDocument();
  });
});

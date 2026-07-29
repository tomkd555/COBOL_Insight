import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect, vi, afterEach } from "vitest";
import { Toast } from "./Toast";

afterEach(() => {
  vi.useRealTimers();
});

describe("Toast", () => {
  it("メッセージが null のときは何も描画しない", () => {
    const { container } = render(<Toast message={null} />);
    expect(container.querySelector(".ci-toast")).toBeNull();
  });

  it("メッセージを aria-live で読み上げる領域として表示する", () => {
    render(<Toast message="解析が完了しました" />);
    const toast = screen.getByRole("status");
    expect(toast).toHaveTextContent("解析が完了しました");
    expect(toast).toHaveAttribute("aria-live", "polite");
  });

  it("既定 2600ms 経過後に onDismiss を呼ぶ", () => {
    vi.useFakeTimers();
    const onDismiss = vi.fn();
    render(<Toast message="保存しました" onDismiss={onDismiss} />);
    expect(onDismiss).not.toHaveBeenCalled();
    vi.advanceTimersByTime(2599);
    expect(onDismiss).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it("durationMs で自動消滅の時間を変えられる", () => {
    vi.useFakeTimers();
    const onDismiss = vi.fn();
    render(<Toast message="x" onDismiss={onDismiss} durationMs={1000} />);
    vi.advanceTimersByTime(1000);
    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it("閉じるボタンを aria-label 付きで表示し、押すと onDismiss を呼ぶ", () => {
    const onDismiss = vi.fn();
    render(<Toast message="保存しました" onDismiss={onDismiss} />);
    const closeButton = screen.getByRole("button", { name: "通知を閉じる" });
    fireEvent.click(closeButton);
    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it("onDismiss を渡さない場合は閉じるボタンを描画しない", () => {
    render(<Toast message="保存しました" />);
    expect(screen.queryByRole("button", { name: "通知を閉じる" })).toBeNull();
  });
});
